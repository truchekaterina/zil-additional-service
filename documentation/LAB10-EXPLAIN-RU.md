# LAB10 - объяснение по шагам (очень простым языком)

Этот файл нужен, чтобы быстро понять и объяснить на защите, что именно сделано в LAB10 в `zil-additional-service`.

## 1) Зачем нужна LAB10

Главная идея:

- не ходить каждый раз в основной сервис за одними и теми же клиентами и машинами;
- хранить клиентов и машины в кеше внутри additional-сервиса;
- периодически писать в лог размер кешей;
- проверить поведение под нагрузкой (CPU 0.5 и 1.0).

Польза:

- меньше HTTP-запросов в основной сервис;
- ниже лишняя нагрузка;
- лучше стабильность и время ответа под нагрузкой.

## 2) Где в проекте это реализовано

- Кеш клиентов: `src/main/java/rental/additional/service/ClientCacheService.java`
- Кеш машин: `src/main/java/rental/additional/service/CarCacheService.java`
- Использование кешей в статистике и availability: `src/main/java/rental/additional/service/AdditionalRentalService.java`
- Интервал логирования кеша: `src/main/resources/application.properties`
- Включение планировщика: `src/main/java/rental/additional/ZilAdditionalServiceApplication.java`
- Подробная методичка по прогону: `documentation/LAB10_MANUAL_FULL_RU.md`

## 3) Как устроен кеш (что внутри)

Классы `ClientCacheService` и `CarCacheService`:

- `ClientCacheService` хранит данные в `ConcurrentHashMap<UUID, ClientDto> byId`;
- `CarCacheService` хранит данные в `ConcurrentHashMap<UUID, CarDto> byId`;
- `reloadFrom(...)`:
  - очищает карту;
  - кладет объекты по ключу `id`;
- `get(UUID id)`:
  - есть в кеше клиентов, возвращает клиента по id;
- `getAll()`:
  - есть в кеше машин, возвращает все машины из кеша списком;
- `size()`:
  - возвращает текущий размер кеша;
- `clear()`:
  - очищает кеш.

Почему `ConcurrentHashMap`:

- сервис работает многопоточно;
- под нагрузкой это безопаснее, чем обычный `HashMap`.

## 4) Как кеш используется в бизнес-логике

В `AdditionalRentalService`:

1. В `getStats()` вызываются `ensureClientCacheForStats()` и `ensureCarCacheForStats()`.
2. Если кеши пустые:
   - выполняется `mainCrudClient.getClients()`;
   - выполняется `mainCrudClient.getCars()`;
   - результат загружается в кеш через `clientCacheService.reloadFrom(...)`.
   - результат загружается в кеш через `carCacheService.reloadFrom(...)`.
3. Количество клиентов и машин берется уже из кеша:
   - `int nCars = carCacheService.size();`
   - `int nClients = clientCacheService.size();`
4. В методах availability машины тоже читаются из кеша через `getCarsFromCache()`.
5. Возвращается `AdditionalStatsDto`.

Идея:

- первый вызов заполняет кеши;
- последующие вызовы используют уже кешированные данные.

## 5) Планировщик в LAB10

В `ClientCacheService` и `CarCacheService` есть:

- `@Scheduled(fixedRateString = "${cache.statistics.log-interval-ms:10000}")`
- метод `logCacheSize()`

Он периодически пишет в лог:

- `[client-cache-statistics] Client cache size=...`
- `[car-cache-statistics] Car cache size=...`

Интервал задается в `application.properties`:

- `cache.statistics.log-interval-ms=10000`

То есть каждые 10 секунд в логе появляются строки с размером кеша клиентов и кеша машин.

## 6) Что важно не путать с LAB9

LAB9:

- измеряет длительности методов (`timed`, окна 10s/30s/1m, агрегация).

LAB10:

- делает кеш клиентов и машин + логирует размеры кешей через `@Scheduled`.

Обе лабы могут одновременно работать в одном сервисе.

## 7) Полный алгоритм LAB10 (коротко, но целиком)

1. Сервис стартует, кеш клиентов и машин пустой.
2. При первом `getStats()` проверяется размер кеша.
3. Если пусто - забираем клиентов и машины из main-сервиса.
4. Складываем их в `ConcurrentHashMap`.
5. Возвращаем статистику, где количество клиентов и машин берется из кешей.
6. В availability машины читаются из кеша, а не напрямую из `mainCrudClient.getCars()`.
7. Параллельно планировщик раз в N мс пишет `Client cache size=...` и `Car cache size=...`.
8. Под нагрузкой повторные запросы меньше зависят от `getClients()` и `getCars()`.

## 8) Что показывать на защите

1. Код кеша:
   - `ClientCacheService`
   - `CarCacheService`
2. Где кеш читается:
   - `AdditionalRentalService#getStats()`, `ensureClientCacheForStats()`, `ensureCarCacheForStats()`, `getCarsFromCache()`
3. Конфиг:
   - `cache.statistics.log-interval-ms` в `application.properties`
4. Логи:
   - строки `[client-cache-statistics] Client cache size=...`
   - строки `[car-cache-statistics] Car cache size=...`
5. Нагрузка:
   - результаты k6 для CPU 0.5 и 1.0 (по методичке LAB10)

## 9) Готовый устный ответ (30-40 секунд)

"В LAB10 я добавила кеш клиентов и машин в additional-сервис. Кеши реализованы на `ConcurrentHashMap` в `ClientCacheService` и `CarCacheService`. В `getStats()` перед расчетом вызывается проверка кешей: если они пустые, один раз загружаю клиентов и машины из main-сервиса и кладу в кеши, дальше использую размеры кешей без повторных запросов. Методы availability тоже читают список машин через кеш. Также добавлены `@Scheduled`-методы, которые с периодом из `application.properties` пишут в лог текущий размер кешей, чтобы было видно, как кеш живет во времени. Затем выполняются нагрузочные прогоны с CPU 0.5 и 1.0 и сохраняются JSON/логи для сравнения."
