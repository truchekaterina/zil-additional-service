# LAB9 Observability - подробный разбор

Этот документ объясняет, как в проекте работает сбор статистики: от замера времени в `timed(...)` до периодической агрегации по окнам `10s`, `30s`, `1m`.

## Где что находится

- Конфиг окон и периода агрегации: `src/main/resources/application.properties`
- Основная логика наблюдаемости: `src/main/java/rental/additional/observability/ObservabilityService.java`
- Тайминги в контроллерах: `src/main/java/rental/additional/controller/AdditionalController.java`
- Тайминги вызовов в основной сервис: `src/main/java/rental/additional/client/RestTemplateMainCrudClient.java`
- Тайминги расчета дополнительной статистики: `src/main/java/rental/additional/service/AdditionalRentalService.java`
- Включение планировщика: `src/main/java/rental/additional/ZilAdditionalServiceApplication.java`

## Ключевые настройки

В `application.properties`:

- `observability.window.short=10s`
- `observability.window.medium=30s`
- `observability.window.long=1m`
- `observability.log-interval=PT5S`

Что это значит:

- `window.*` - это три длительности окон агрегации.
- `log-interval` - как часто запускать расчет сводки.
- `PT5S` - ISO-формат длительности: "5 секунд".

## Полный алгоритм (простыми шагами)

1. Любой участок кода, обернутый в `observabilityService.timed("category", ...)`, замеряется по времени.
2. После выполнения кода создается запись наблюдения:
   - когда произошло событие (`instant`),
   - категория (`category`),
   - длительность (`durationNanos`).
3. Запись кладется в очередь `observations`.
4. Планировщик (`@Scheduled`) раз в `log-interval` вызывает `logAggregates()`.
5. `logAggregates()`:
   - берет текущее время `now`,
   - вычисляет самое большое окно из трех,
   - удаляет записи старше этого максимального окна,
   - запускает агрегацию для каждого окна: `10s`, `30s`, `1m`.
6. `emitWindow(...)` для каждого окна:
   - оставляет только записи внутри окна (`now - window ... now`),
   - группирует по категории,
   - считает:
     - `count` - число событий,
     - `sumNanos` - сумму длительностей,
     - `avgNanos` - среднюю длительность,
   - пишет сводку в лог.

## Схема вызовов

```mermaid
flowchart TD
    A[HTTP request] --> B[AdditionalController timed web.additional.*]
    B --> C[AdditionalRentalService timed additional.statsComputation.*]
    C --> D[RestTemplateMainCrudClient timed http.main.*]
    D --> E[Main CRUD service]

    B --> F[ObservabilityService.record]
    C --> F
    D --> F
    F --> G[(observations queue)]

    H[@Scheduled every log-interval] --> I[logAggregates]
    I --> J[remove too old records]
    I --> K[emitWindow short]
    I --> L[emitWindow medium]
    I --> M[emitWindow long]
    K --> N[log count/sum/avg by category]
    L --> N
    M --> N
```

## Разбор `ObservabilityService` по методам

### Поля

- `windowShort`, `windowMedium`, `windowLong` - длительности трех окон.
- `observations` - потокобезопасная очередь событий (`ConcurrentLinkedQueue`).
- `clock` - источник времени.
- `log` - логгер.

### Конструктор

Получает окна из `application.properties` через `@Value(...)` и сохраняет в поля.

### `max(Duration a, Duration b, Duration c)`

Возвращает максимальную длительность из трех. Нужен для вычисления горизонта хранения событий.

### `record(String category, long durationNanos)`

Создает объект `Observation` и добавляет его в очередь `observations`.

### `timed(String category, Supplier<T> supplier)`

1. Запоминает время старта (`System.nanoTime()`).
2. Выполняет код `supplier.get()`.
3. В `finally` считает длительность и вызывает `record(...)`.

Важно: запись произойдет даже если внутри `supplier` случилась ошибка, потому что используется `finally`.

### `runTimed(String category, Runnable runnable)`

То же самое, что `timed(...)`, но для кода без возвращаемого значения.

### `logAggregates()`

Периодический метод, запускается планировщиком:

1. Берет `now`.
2. Считает `retention = max(windowShort, windowMedium, windowLong)`.
3. Удаляет записи старше `now - retention`.
4. Вызывает:
   - `emitWindow(now, windowShort, "10s")`
   - `emitWindow(now, windowMedium, "30s")`
   - `emitWindow(now, windowLong, "1m")`

### `emitWindow(Instant now, Duration window, String label)`

1. Считает начало окна: `from = now.minus(window)`.
2. Проходит по всем наблюдениям.
3. Пропускает все, что старше `from`.
4. Для остальных агрегирует по `category`:
   - `row[0]` - count,
   - `row[1]` - sum.
5. Вычисляет `avg = sum / count`.
6. Пишет сводку в лог с меткой окна (`label`).

### `Observation`

Внутренний `record`-тип с 3 полями:

- `instant`
- `category`
- `durationNanos`

## Где именно вызывается `timed(...)`

### В контроллере (`AdditionalController`)

- `health()` -> `web.additional.health`
- `getAvailability(...)` -> `web.additional.availability`
- `getAvailabilityNew(...)` -> `web.additional.availability_new`
- `getStats()` -> `web.additional.stats`

Что это дает: видно время обработки HTTP-endpoint'ов.

### В клиенте к основному сервису (`RestTemplateMainCrudClient`)

- `getCars()` / `getClients()` / `getRents()` через `getList(...)`
- категории:
  - `http.main.cars`
  - `http.main.clients`
  - `http.main.rents`

Что это дает: видно время сетевых вызовов в основной CRUD.

### В сервисе бизнес-логики (`AdditionalRentalService`)

Категории:

- `additional.statsComputation.availability.cityDate`
- `additional.statsComputation.availability.allCitiesForDate`
- `additional.statsComputation.availability.allCitiesAllDates`
- `additional.statsComputation.availability.cityAllDates`
- `additional.statsComputation.stats`

Что это дает: видно время расчета дополнительной статистики.

## Как читать логи

Формат:

`LAB9 Observability window=<label> category=<cat> count=<n> sumNanos=<sum> avgNanos=<avg>`

Интерпретация:

- `window` - за какой период считали (10s / 30s / 1m)
- `category` - какой тип операции
- `count` - сколько вызовов за окно
- `sumNanos` - общее время всех вызовов категории
- `avgNanos` - среднее время одного вызова

## Важные нюансы

1. Это in-memory статистика: после перезапуска приложения история очищается.
2. Очередь событий очищается от устаревших записей, чтобы не росла бесконечно.
3. Три окна считаются независимо, поэтому можно видеть "короткий срез" и "более длинный тренд".
4. Метки `"10s"`, `"30s"`, `"1m"` в вызовах `emitWindow(...)` - это подписи для лога; сами длительности окон берутся из `Duration` полей, загруженных из конфигурации.
