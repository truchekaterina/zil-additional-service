# LAB8 — вынос проверки доступности автомобилей в Additional service

Документ для подготовки и защиты LAB8 по проекту `zil`, секция **«8. Сервис аренды автомобилей»**. Цель лабораторной: оставить основной Spring Boot CRUD-сервис сервисом данных, а реальную логику из раздела **«Дополнительно»** вынести в отдельный микросервис **Additional service**. Для этой секции дополнительная функция формулируется так: **проверять доступность автомобиля на дату в городе**.

Из LAB7 уже зафиксированы рабочие значения для этого проекта:

- ФИО: `Трюх Екатерина`;
- основной репозиторий: `https://github.com/tryuchekaterina/Labs_hls`;
- LAB7 branch/commit: `lab7`, `272f817 LAB7: подключить приложение к удаленной БД`, ветка запушена и совпадает с `origin/lab7`;
- app-нода: `hl07`, вход `ssh -p 2307 hl@hlssh.zil.digital`;
- DB-нода: `hl12`, вход `ssh -p 2312 hl@hlssh.zil.digital`;
- DB host внутри учебной сети: `hl12.zil`, IP `10.60.3.9`;
- DB name/user/schema: `hl7`, внешний порт PostgreSQL `5437`, внутренний порт контейнера `5432`.

Пароли Harbor, DB и другие секреты в публичный репозиторий не добавляйте: ниже они остаются placeholders вида `<HARBOR_PASSWORD>` и `<DB_PASSWORD>`. Значение `hl7` здесь используется как имя БД/пользователь/схема; если на учебной ВМ пароль тоже задан как `hl7`, это всё равно лучше держать в серверном `.env` или вводить на ВМ, а не фиксировать в публичной документации.

---

## 1. Что уже есть в проекте

Перед LAB8 полезно понимать, от чего мы отталкиваемся.

### Основной сервис `zil`

Проект `zil` — это Spring Boot CRUD-приложение для аренды автомобилей:

- `CarController` отдаёт CRUD по машинам: `GET/POST/PUT/DELETE /cars`;
- `ClientController` отдаёт CRUD по клиентам: `GET/POST/PUT/DELETE /clients`;
- `RentController` отдаёт CRUD по арендам: `GET/POST/PUT/DELETE /rents`;
- `StatsController` отдаёт простую агрегированную статистику `GET /stats`;
- приложение слушает HTTP-порт `8083`;
- PostgreSQL подключается через переменные окружения `DBHOST`, `DBPORT`, `DBNAME`, `SCHEMANAME`;
- Flyway создаёт таблицы `cars`, `clients`, `rents`;
- в `docker-compose.yml` уже есть CPU-лимит через `cpus: "${APP_CPUS:-1.0}"`;
- в `k6` уже есть сценарии и отчёты LAB6.

### Модель данных

В БД три основные таблицы:

| Таблица | Смысл | Важные поля |
| --- | --- | --- |
| `cars` | автомобили | `id`, `vin`, `model`, `city`, `salon_name`, `rental_cost_per_day` |
| `clients` | клиенты | `id`, `full_name`, `driver_license`, `phone` |
| `rents` | аренды | `id`, `car_id`, `client_id`, `start_date`, `end_date`, `total_cost` |

В Java-коде `Rent` специально хранит только `carId` и `clientId`, без JPA-связей `@ManyToOne`. Это удобно для LAB8: Additional service может получить список машин и аренд по REST, отфильтровать машины по городу, найти аренды, пересекающиеся с выбранной датой, и уже в Java определить доступные/недоступные автомобили.

### Текущий `docker-compose.yml`

В `zil/docker-compose.yml` сейчас основной сервис `app` уже настроен на удалённую БД LAB7:

```yaml
services:
  postgres:
    image: postgres:16-alpine
    container_name: zil-postgres
    ports:
      - "5433:5432"

  app:
    build:
      context: .
      dockerfile: Dockerfile
    container_name: zil-app
    environment:
      DBHOST: hl12.zil
      DBPORT: "5437"
      DBNAME: hl7
      SCHEMANAME: hl7
      SPRING_DATASOURCE_USERNAME: hl7
      SPRING_DATASOURCE_PASSWORD: <DB_PASSWORD>
      SPRING_JPA_SHOW_SQL: "false"
      SERVER_TOMCAT_THREADS_MAX: "50"
    ports:
      - "8083:8083"
    cpus: "${APP_CPUS:-1.0}"
    mem_limit: "${APP_MEM:-768m}"
```

`depends_on: postgres` у `app` убран, потому что приложение в LAB7 ходит не в локальный compose-PostgreSQL, а на DB-ноду `hl12.zil:5437`. Локальный сервис `postgres` в compose можно оставить как вспомогательный для IDE/bootRun, но для защиты LAB8 рабочая цепочка такая: `app -> hl12.zil:5437 -> PostgreSQL`.

В `src/main/resources/application.properties` основного сервиса уже стоит нужная JDBC-строка. В публичной документации пароль лучше показывать как placeholder:

```properties
spring.datasource.url=jdbc:postgresql://${DBHOST:localhost}:${DBPORT:5437}/${DBNAME:hl7}?currentSchema=${SCHEMANAME:hl7}
spring.datasource.username=${SPRING_DATASOURCE_USERNAME:hl7}
spring.datasource.password=${SPRING_DATASOURCE_PASSWORD:<DB_PASSWORD>}
```

Для LAB8 это хорошая база. Нужно добавить второй сервис `additional`, у которого будет свой Docker-образ и свой CPU-лимит.

---

## 2. Подходит ли текущий проект для LAB8

Короткий вывод: **да, проект подходит для реализации ТЗ LAB8**.

Почему подходит:

- основной сервис уже разделён на контроллеры, сервисы и репозитории;
- есть стабильные HTTP-эндпоинты `/cars`, `/clients`, `/rents`, `/stats`;
- есть Dockerfile и compose, поэтому второй сервис можно добавить без переписывания всего проекта;
- есть k6-сценарий LAB6 и скрипт построения графиков;
- `Rent` хранит внешние ключи как UUID, поэтому Java-side join естественно делать в новом сервисе;
- приложение уже умеет работать с удалённой БД после LAB7.

Что нужно учесть:

- сейчас часть дополнительной логики может быть реализована в основном сервисе как `/rents/availability` или `/rents/availability/count` через `CarRepository`/JPQL. Для LAB8 это нельзя показывать как основную реализацию, потому что join/anti-join фактически делается в БД;
- для требования «Join должен происходить на стороне Java Additional service» нужно перенести или повторить эту бизнес-логику в Additional service через REST-вызовы `/cars` и `/rents`;
- старый endpoint в основном сервисе можно оставить для совместимости, но на защите показывать именно Additional endpoint доступности;
- основной CRUD-сервис не должен подключаться к Additional service, зависимость должна идти в одну сторону: Additional service вызывает CRUD-сервис;
- Additional service не должен подключаться напрямую к PostgreSQL.

---

## 3. Что именно выносить в Additional service

Рекомендуемый вариант для этой лабораторной: вынести именно реальную функцию раздела **«Дополнительно»** — проверку доступности авто на дату в городе.

1. Оставить в основном сервисе CRUD:
   - `/cars`;
   - `/cars/{id}`;
   - `/clients`;
   - `/clients/{id}`;
   - `/rents`;
   - `/rents/{id}`.

2. Вынести в Additional service дополнительную бизнес-логику:
   - `GET /additional/cars/availability?city=Moscow&date=2026-04-03` — главный endpoint LAB8, проверка доступности машин в городе на дату;
   - альтернативное имя, если хочется короче: `GET /additional/cars/available?city=Moscow&date=2026-04-03`;
   - `GET /additional/stats` — опциональная статистика по машинам, клиентам и арендам через HTTP-вызовы основного сервиса;
   - `GET /additional/rents/details` — опциональный демо-endpoint, где аренда обогащается машиной и клиентом. Он полезен для демонстрации Java-side join, но не должен быть главным сценарием LAB8.

Самый показательный endpoint для LAB8:

```text
GET /additional/cars/availability?city=Moscow&date=2026-04-03
```

Он делает:

1. HTTP `GET http://app:8083/cars`;
2. HTTP `GET http://app:8083/rents`;
3. фильтрует автомобили по `city`;
4. выбирает аренды, у которых выбранная `date` попадает в интервал `[startDate, endDate]`;
5. строит `Set<UUID>` арендованных `carId`;
6. возвращает машины из города с признаком `available`, либо отдельные списки `availableCars` и `unavailableCars`.

Это и есть Java-side join/фильтрация на стороне Additional service. В БД при этом не выполняется SQL join, Additional service не имеет datasource и не использует `CarRepository`.

---

## 4. Итоговая архитектура LAB8

Финальная схема:

```text
Ваш ПК / k6
  |
  | HTTP через SSH-туннель или напрямую с K6-ноды
  v
Прикладная ВМ
  |
  | docker compose
  |
  +-- main-app / zil-app
  |     порт внутри compose: 8083
  |     назначение: CRUD + работа с PostgreSQL
  |
  +-- additional-service
        порт внутри compose: 8084
        назначение: проверка доступности + Java-side join/filter
        вызывает main-app по HTTP:
        http://app:8083/cars
        http://app:8083/rents
        опционально: http://app:8083/clients
```

Если LAB7 уже сделана, то основной сервис `app` подключается к удалённой DB-ноде `hl12.zil`, а Additional service к БД вообще не ходит:

```text
additional-service
  |
  | HTTP
  v
main CRUD service
  |
  | JDBC
  v
PostgreSQL на DB-ноде
```

---

## 5. Данные LAB7 и таблицы курса

Для этой работы уже известны основные значения из LAB7:

| Что | Где взять | Пример/заметка |
| --- | --- | --- |
| ФИО | текущая строка студента | `Трюх Екатерина` |
| GitHub repo основного сервиса | LAB7 | `https://github.com/tryuchekaterina/Labs_hls` |
| LAB7 branch/commit | Git | `lab7`, `272f817 LAB7: подключить приложение к удаленной БД` |
| Основной SSH host | таблица/методичка | `hlssh.zil.digital` |
| App-нода | таблица ресурсов | `hl07` |
| SSH к app-ноде | LAB7 | `ssh -p 2307 hl@hlssh.zil.digital` |
| DB-нода | таблица ресурсов | `hl12` |
| SSH к DB-ноде | LAB7 | `ssh -p 2312 hl@hlssh.zil.digital` |
| SSH-пользователь | таблица ВМ | `hl` |
| Пароль SSH | таблица ВМ | не вставляйте в команды и Git |
| Harbor host/port | таблица ВМ | порт Harbor может быть `2313`, но проверьте |
| Harbor user/password | таблица ВМ | берите из таблицы, не коммитьте |
| DB host/IP | LAB7 | `hl12.zil`, `10.60.3.9` |
| DB name/user/schema | LAB7 | `hl7` |
| PostgreSQL port | LAB7 | внешний `5437`, внутри контейнера `5432` |
| Новый Git repo Additional service | создаёте отдельно | эту ссылку нужно вставить в колонку `Additional service` |

Важно: реальные пароли из таблицы не пишите в `README`, `docker-compose.yml`, историю команд в отчёте и Git. В командах ниже используются placeholders вида `<DB_PASSWORD>` и `<HARBOR_PASSWORD>`.

Практическая деталь по текущему репозиторию: `.gitignore` сейчас имеет незакоммиченное изменение и игнорирует `zil/documentation/`. Поэтому LAB7/LAB8 документация не попадёт в commit обычным `git add`; для коммита документации понадобится `git add -f zil/documentation/LAB8_PLAN_RU.md` или отдельное изменение ignore rule. В этом плане `.gitignore` не меняем.

---

## 6. Где и что вводить

### На Windows/вашем ПК

Используется для:

- подключения по SSH к ВМ;
- создания/пуша Git-репозитория Additional service;
- открытия Swagger через SSH-туннель;
- локальной подготовки кода, если пишете сервис в IDE.

Подключение к прикладной ВМ:

```powershell
ssh -p 2307 hl@hlssh.zil.digital
```

Туннель к основному сервису:

```powershell
ssh -p 2307 -L 8080:127.0.0.1:8083 hl@hlssh.zil.digital
```

Здесь Swagger основного сервиса на Windows открывается как `http://localhost:8080/swagger-ui/index.html`, потому что туннель мапит порт Windows `8080` на порт `8083` внутри app VM.

Туннель к Additional service:

```powershell
ssh -p 2307 -L 8084:127.0.0.1:8084 hl@hlssh.zil.digital
```

После этого в браузере на Windows:

```text
http://localhost:8080/swagger-ui/index.html
http://localhost:8084/swagger-ui/index.html
http://localhost:8084/additional/cars/availability?city=Moscow&date=2026-04-03
```

### На прикладной ВМ

Используется для:

- клонирования основного репозитория и репозитория Additional service;
- сборки Docker-образов;
- запуска `docker compose`;
- проверки контейнеров;
- локальных curl-проверок.

Типовой вход:

```bash
ssh -p 2307 hl@hlssh.zil.digital
```

Проверки после входа:

```bash
hostname
pwd
docker --version
docker compose version
```

### На Harbor/registry

Используется для:

- публикации отдельного Docker-образа Additional service;
- указания image в compose;
- демонстрации, что сервис действительно собран отдельно.

Логин выполняйте только с реальным host/port из таблицы:

```bash
docker login <HARBOR_HOST>:<HARBOR_PORT>
```

Когда Docker попросит:

```text
Username: <HARBOR_USER>
Password: <HARBOR_PASSWORD>
```

Не вставляйте пароль в команду `docker login -p ...`, чтобы он не остался в истории shell.

### На K6-ноде

Используется для снятия графиков сервер-сервер:

```text
k6 -> Additional service на прикладной ВМ -> Main CRUD service -> PostgreSQL
```

Если k6 запускается на отдельной K6-ноде, в `BASE_URL` указывайте адрес прикладной ВМ, доступный из внутренней сети курса:

```bash
BASE_URL=http://<APP_VM_INTERNAL_HOST_OR_IP>:8084 k6 run load-lab8-s2s.js
```

Если k6 запускается прямо на прикладной ВМ, можно использовать:

```bash
BASE_URL=http://localhost:8084 k6 run load-lab8-s2s.js
```

### На DB-ноде

Для LAB8 обычно не нужно деплоить ничего нового на DB-ноду. Там уже работает PostgreSQL и pgAdmin из LAB7 в каталоге `~/katya`. Порт `5432` на DB-ноде был занят, поэтому PostgreSQL проброшен как `5437:5432`, контейнер запущен с `postgres -c max_connections=1000`, проверка `SHOW max_connections;` дала `1000`.

Подключение к DB-ноде:

```bash
ssh -p 2312 hl@hlssh.zil.digital
```

pgAdmin через Windows-туннель:

```powershell
ssh -p 2312 -L 8081:127.0.0.1:8081 hl@hlssh.zil.digital
```

Открывать на Windows:

```text
http://localhost:8081
```

Additional service на DB-ноду не ставится и к PostgreSQL не подключается напрямую. Единственная связь с DB-нодой остаётся прежней:

```text
main CRUD service -> JDBC -> PostgreSQL
```

Если основной сервис уже стабильно работает с удалённой БД после LAB7, на DB-ноду для LAB8 заходят только для диагностики, например посмотреть контейнер PostgreSQL или логи.

Из LAB7 также проверено:

```text
getent hosts hl12.zil -> 10.60.3.9 hl12.zil
nc -vz hl12.zil 5437 -> succeeded
Flyway применил V1__init_schema.sql и V2__seed_data.sql
В БД есть cars, clients, rents, flyway_schema_history
В cars/clients/rents по 4 строки
```

---

## 7. Отдельный репозиторий Additional service

По ТЗ Additional service должен быть в отдельном репозитории.

Рекомендуемое имя:

```text
zil-additional-service
```

Пример структуры:

```text
zil-additional-service/
  build.gradle
  settings.gradle
  Dockerfile
  README.md
  src/
    main/
      java/
        rental/additional/
          AdditionalServiceApplication.java
          config/
            MainServiceClientConfig.java
          client/
            MainCrudClient.java
          controller/
          AdditionalController.java
          dto/
            CarDto.java
            ClientDto.java
            RentDto.java
            CarAvailabilityDto.java
            AvailabilityResponseDto.java
            RentDetailsDto.java
            AdditionalStatsDto.java
          service/
            AdditionalRentalService.java
      resources/
        application.properties
    test/
      java/
        rental/additional/
          AdditionalRentalServiceTest.java
```

Почему отдельный репозиторий, а не папка внутри `zil`:

- в таблице ресурсов есть отдельная колонка `Additional service`;
- Docker-образ должен собираться отдельно;
- сервис должен быть независимым артефактом;
- так проще показать преподавателю границу микросервиса.

Что указать в Google Sheet:

```text
Additional service: https://github.com/<your-login>/zil-additional-service
```

Если используете GitLab или другой хостинг, вставьте публичную или доступную преподавателю ссылку на репозиторий.

---

## 8. Рекомендуемый HTTP-клиент

По ТЗ Additional service должен вызывать основной CRUD-сервис через один из вариантов:

- `RestTemplate`;
- `WebClient` в синхронном режиме;
- Feign Client.

Рекомендация для этого проекта: **RestTemplate**.

Почему:

- основной проект уже использует Spring MVC (`spring-boot-starter-web`);
- RestTemplate не требует Spring Cloud;
- вызовы будут синхронными и понятными для защиты;
- в LAB8 важнее показать границу сервисов и Java-side join, чем усложнять стек.

WebClient тоже можно использовать, но тогда обычно добавляют `spring-boot-starter-webflux` и вызывают `.block()`. Feign удобен, но тянет Spring Cloud и добавляет больше конфигурации. Для учебного проекта это лишняя сложность.

---

## 9. Контракт между сервисами

### Основной CRUD-сервис

Additional service читает эти endpoint'ы:

```text
GET http://app:8083/cars
GET http://app:8083/cars/{id}
GET http://app:8083/clients
GET http://app:8083/clients/{id}
GET http://app:8083/rents
GET http://app:8083/rents/{id}
```

Внутри одного Docker Compose имя `app` — это DNS-имя сервиса. Поэтому Additional service должен ходить не на `localhost:8083`, а на:

```text
http://app:8083
```

Почему не `localhost`: внутри контейнера `localhost` означает сам контейнер Additional service, а не основной сервис.

### Additional service

Рекомендуемые endpoint'ы:

```text
GET /additional/health
GET /additional/cars/availability?city=Moscow&date=2026-04-03
GET /additional/cars/available?city=Moscow&date=2026-04-03
GET /additional/stats
GET /additional/rents/details              # опционально
GET /additional/rents/{id}/details         # опционально
```

Основной endpoint для защиты:

```text
GET /additional/cars/availability?city=Moscow&date=2026-04-03
```

Пример ответа:

```json
{
  "city": "Moscow",
  "date": "2026-04-03",
  "availableCars": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440001",
      "vin": "WVWZZZ3CZWE123456",
      "model": "Toyota Camry",
      "color": "Black",
      "rentalCostPerDay": 50.00,
      "city": "Moscow",
      "salonName": "Salon A"
    },
    {
      "id": "550e8400-e29b-41d4-a716-446655440004",
      "vin": "WBA3B1C50EK123456",
      "model": "BMW 320",
      "color": "Blue",
      "rentalCostPerDay": 80.00,
      "city": "Moscow",
      "salonName": "Premium Salon"
    }
  ],
  "unavailableCars": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440002",
      "vin": "1HGBH41JXMN109186",
      "model": "Honda Accord",
      "color": "White",
      "rentalCostPerDay": 45.50,
      "city": "Moscow",
      "salonName": "Salon B"
    }
  ]
}
```

Такой пример совпадает с seed-данными проекта: на `2026-04-03` в `Moscow` занята `Honda Accord`, потому что у неё есть аренда `2026-04-01` — `2026-04-07`; `Toyota Camry` и `BMW 320` в эту дату свободны.

Можно вернуть и плоский список машин с полем `available: true/false`. Главное, чтобы доступность считалась в Additional service на основе `/cars` и `/rents`, а не SQL-запросом.

Пример ответа `GET /additional/stats`:

```json
{
  "cars": 4,
  "clients": 4,
  "rents": 4,
  "source": "main-crud-service"
}
```

Опциональный пример ответа `GET /additional/rents/details`:

```json
[
  {
    "id": "11111111-1111-1111-1111-111111111111",
    "startDate": "2026-04-01",
    "endDate": "2026-04-05",
    "totalCost": 15000.00,
    "car": {
      "id": "22222222-2222-2222-2222-222222222222",
      "model": "Toyota Camry",
      "city": "Moscow",
      "salonName": "Central"
    },
    "client": {
      "id": "33333333-3333-3333-3333-333333333333",
      "fullName": "Ivan Petrov",
      "phone": "+79000000000"
    }
  }
]
```

Поля можно оставить такими же, как в основном сервисе. Для `/additional/rents/details` важно, чтобы было видно: `Rent` + `Car` + `Client` собраны в одном ответе именно в Additional service. Но для этой секции главным endpoint'ом считается availability.

---

## 10. Минимальная логика Java-side join/filter

Псевдокод главного сервиса доступности:

```java
public AvailabilityResponseDto getAvailability(String city, LocalDate date) {
    List<CarDto> cars = mainCrudClient.getCars();
    List<RentDto> rents = mainCrudClient.getRents();

    List<CarDto> carsInCity = cars.stream()
            .filter(car -> car.city().equalsIgnoreCase(city))
            .toList();

    Set<UUID> rentedCarIds = rents.stream()
            .filter(rent -> !date.isBefore(rent.startDate())
                    && !date.isAfter(rent.endDate()))
            .map(RentDto::carId)
            .collect(Collectors.toSet());

    List<CarDto> available = carsInCity.stream()
            .filter(car -> !rentedCarIds.contains(car.id()))
            .toList();

    List<CarDto> unavailable = carsInCity.stream()
            .filter(car -> rentedCarIds.contains(car.id()))
            .toList();

    return new AvailabilityResponseDto(city, date, available, unavailable);
}
```

Если нужно показать ещё и классический join, можно оставить опциональный `/additional/rents/details`: Additional service получает `/rents`, `/cars`, `/clients`, строит `Map<UUID, CarDto>` и `Map<UUID, ClientDto>`, затем собирает `RentDetailsDto`. Но это дополнительная демонстрация, не основная функция секции.

Если в данных аренды есть `carId`, которого нет в `/cars`, выберите одно поведение и держите его одинаковым:

- для учебной защиты проще игнорировать такую аренду и объяснить, что данные в CRUD-сервисе неконсистентны;
- для более строгого варианта можно бросать `IllegalStateException`/возвращать `502 Bad Gateway`, потому что Additional service получил неконсистентные данные от upstream-сервиса.

На защите важно проговорить:

- основной сервис отдаёт сырые сущности `/cars` и `/rents` через REST;
- Additional service получает данные по HTTP через `RestTemplate`, sync `WebClient` или Feign;
- Additional service фильтрует машины по городу;
- Additional service ищет аренды, пересекающиеся с датой, и строит `Set<UUID>` занятых машин;
- объединение/фильтрация происходят в памяти Java;
- Additional service не выполняет SQL-запросы и не имеет JDBC datasource.

---

## 11. Пример `application.properties` Additional service

В новом репозитории:

```properties
server.port=${SERVER_PORT:8084}

main-service.base-url=${MAIN_SERVICE_BASE_URL:http://localhost:8083}

springdoc.api-docs.enabled=true
springdoc.swagger-ui.enabled=true
springdoc.swagger-ui.path=/swagger-ui.html
```

Для Docker Compose переменная будет:

```yaml
MAIN_SERVICE_BASE_URL: http://app:8083
```

Для локального запуска на Windows:

```powershell
$env:MAIN_SERVICE_BASE_URL = "http://localhost:8083"
.\gradlew bootRun
```

---

## 12. Пример Dockerfile Additional service

В отдельном репозитории `zil-additional-service`:

```dockerfile
FROM eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /app

COPY gradlew .
COPY gradle gradle
COPY gradle.properties .
COPY settings.gradle .
COPY build.gradle .
COPY src src

RUN ["java", "-classpath", "gradle/wrapper/gradle-wrapper.jar", "org.gradle.wrapper.GradleWrapperMain", "clean", "bootJar", "-x", "test", "--no-daemon"]

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8084
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

Если в Additional service используете Java 17, можно взять `eclipse-temurin:17-jdk-alpine` и `17-jre-alpine`. Для согласованности с текущим проектом допустимо оставить Java 25.

---

## 13. Сборка и публикация Docker-образа

На прикладной ВМ или локально, где есть Docker:

```bash
git clone https://github.com/<your-login>/zil-additional-service.git
cd zil-additional-service
docker build -t <HARBOR_HOST>:<HARBOR_PORT>/<PROJECT>/zil-additional-service:lab8 .
```

Логин в Harbor:

```bash
docker login <HARBOR_HOST>:<HARBOR_PORT>
```

Docker спросит логин и пароль. Вводите значения из таблицы курса вручную.

Публикация:

```bash
docker push <HARBOR_HOST>:<HARBOR_PORT>/<PROJECT>/zil-additional-service:lab8
```

Проверка:

```bash
docker pull <HARBOR_HOST>:<HARBOR_PORT>/<PROJECT>/zil-additional-service:lab8
```

Что показать преподавателю:

```bash
docker images | grep zil-additional-service
docker manifest inspect <HARBOR_HOST>:<HARBOR_PORT>/<PROJECT>/zil-additional-service:lab8
```

Если `manifest inspect` недоступен или registry требует логин, достаточно показать успешный `docker push` и страницу образа в Harbor.

---

## 14. Compose для двух сервисов

Для LAB8 можно использовать один `docker-compose.yml`, как разрешено в ТЗ. Ниже пример идеи для прикладной ВМ.

Если основной сервис собирается локально из `zil/Dockerfile`, а Additional service берётся из Harbor:

```yaml
services:
  app:
    build:
      context: .
      dockerfile: Dockerfile
    container_name: zil-app
    environment:
      DBHOST: hl12.zil
      DBPORT: "5437"
      DBNAME: hl7
      SCHEMANAME: hl7
      SPRING_DATASOURCE_USERNAME: hl7
      SPRING_DATASOURCE_PASSWORD: <DB_PASSWORD>
      SPRING_JPA_SHOW_SQL: "false"
      SERVER_TOMCAT_THREADS_MAX: "50"
    ports:
      - "8083:8083"
    cpus: "${APP_CPUS:-1.0}"
    mem_limit: "${APP_MEM:-768m}"

  additional:
    image: <HARBOR_HOST>:<HARBOR_PORT>/<PROJECT>/zil-additional-service:lab8
    container_name: zil-additional-service
    environment:
      MAIN_SERVICE_BASE_URL: http://app:8083
      SERVER_PORT: "8084"
    ports:
      - "8084:8084"
    depends_on:
      - app
    cpus: "${ADDITIONAL_CPUS:-1.0}"
    mem_limit: "${ADDITIONAL_MEM:-512m}"
```

Если хотите собирать оба сервиса на ВМ из двух папок:

```text
~/work/
  Labs_hls/
    zil/
      docker-compose.yml
  zil-additional-service/
    Dockerfile
```

Тогда в compose можно указать:

```yaml
  additional:
    build:
      context: ../zil-additional-service
      dockerfile: Dockerfile
```

Но для ТЗ лучше финально использовать `image: ...`, чтобы показать опубликованный отдельный Docker-образ.

---

## 15. CPU 0.5 и 1.0

По ТЗ ресурсы CPU нужно задать явно: `0.5` или `1.0`.

В compose используйте:

```yaml
cpus: "${APP_CPUS:-1.0}"
```

и для Additional service:

```yaml
cpus: "${ADDITIONAL_CPUS:-1.0}"
```

Для прогона CPU `0.5` на прикладной ВМ:

```bash
export APP_CPUS=0.5
export ADDITIONAL_CPUS=0.5
docker compose up -d --force-recreate
docker inspect zil-app --format '{{.HostConfig.NanoCpus}}'
docker inspect zil-additional-service --format '{{.HostConfig.NanoCpus}}'
```

Ожидаемые значения:

```text
0.5 CPU -> 500000000
1.0 CPU -> 1000000000
```

Для прогона CPU `1.0`:

```bash
export APP_CPUS=1.0
export ADDITIONAL_CPUS=1.0
docker compose up -d --force-recreate
docker inspect zil-app --format '{{.HostConfig.NanoCpus}}'
docker inspect zil-additional-service --format '{{.HostConfig.NanoCpus}}'
```

Почему задаём лимит двум сервисам: нагрузка LAB8 идёт через Additional service, но он вызывает основной сервис. Если ограничить только один контейнер, графики будет сложнее объяснять.

---

## 16. Полный порядок выполнения LAB8

### Шаг 1. Проверить основной проект

На прикладной ВМ:

```bash
ssh -p 2307 hl@hlssh.zil.digital
cd ~/work/Labs_hls/zil
git status
docker compose up --build -d app
docker compose ps
curl http://localhost:8083/cars
curl http://localhost:8083/clients
curl http://localhost:8083/rents
```

Если каталог другой, сначала найдите, где лежит репозиторий:

```bash
pwd
ls
```

### Шаг 2. Создать отдельный репозиторий

На GitHub/GitLab:

1. Создайте репозиторий `zil-additional-service`.
2. Сделайте его публичным или доступным преподавателю.
3. Скопируйте ссылку.
4. Вставьте ссылку в Google Sheet в колонку `Additional service`.

Пример значения:

```text
https://github.com/<your-login>/zil-additional-service
```

### Шаг 3. Реализовать Additional service

В новом репозитории:

```bash
git clone https://github.com/<your-login>/zil-additional-service.git
cd zil-additional-service
```

Создайте Spring Boot приложение:

- `server.port=8084`;
- DTO для `Car`, `Client`, `Rent`;
- DTO для ответа доступности: например `AvailabilityResponseDto` и `CarAvailabilityDto`;
- клиент `MainCrudClient`;
- сервис `AdditionalRentalService`;
- контроллер `AdditionalController`;
- Swagger/OpenAPI, если хотите удобную проверку.

Главный endpoint:

```text
GET /additional/cars/availability?city=Moscow&date=2026-04-03
```

Он должен возвращать доступные и недоступные машины в городе на выбранную дату. `/additional/rents/details` можно реализовать дополнительно, но не строить вокруг него защиту.

### Шаг 4. Локально проверить связь сервисов

Если основной сервис доступен на `localhost:8083`:

```bash
export MAIN_SERVICE_BASE_URL=http://localhost:8083
./gradlew bootRun
```

В другом терминале:

```bash
curl http://localhost:8084/additional/health
curl http://localhost:8084/additional/stats
curl "http://localhost:8084/additional/cars/availability?city=Moscow&date=2026-04-03"
```

### Шаг 5. Собрать и опубликовать образ

```bash
docker build -t <HARBOR_HOST>:<HARBOR_PORT>/<PROJECT>/zil-additional-service:lab8 .
docker login <HARBOR_HOST>:<HARBOR_PORT>
docker push <HARBOR_HOST>:<HARBOR_PORT>/<PROJECT>/zil-additional-service:lab8
```

Пароль вводите интерактивно, не добавляйте его в команду.

### Шаг 6. Добавить сервис в compose основного проекта

В `zil/docker-compose.yml` добавьте сервис `additional` по примеру из раздела 14. Если не хотите менять основной compose для разработки, можно создать отдельный файл на ВМ:

```bash
nano docker-compose.lab8.yml
```

И запускать:

```bash
docker compose -f docker-compose.yml -f docker-compose.lab8.yml up -d
```

Для защиты проще иметь один понятный compose, но отдельный override тоже допустим, если вы можете объяснить его назначение.

### Шаг 7. Запустить два сервиса

На прикладной ВМ:

```bash
export APP_CPUS=1.0
export ADDITIONAL_CPUS=1.0
docker compose pull additional
docker compose up --build -d
docker compose ps
```

Проверить логи:

```bash
docker compose logs app --tail 80
docker compose logs additional --tail 80
```

Проверить API:

```bash
curl http://localhost:8083/cars
curl http://localhost:8084/additional/stats
curl "http://localhost:8084/additional/cars/availability?city=Moscow&date=2026-04-03"
```

### Шаг 8. Проверить из Windows через туннель

В PowerShell:

```powershell
ssh -p 2307 -L 8084:127.0.0.1:8084 hl@hlssh.zil.digital
```

В браузере:

```text
http://localhost:8084/additional/stats
http://localhost:8084/additional/cars/availability?city=Moscow&date=2026-04-03
http://localhost:8084/swagger-ui/index.html
```

---

## 17. Нагрузка и графики как в LAB6

ТЗ требует снять графики как в LAB6 для CPU `0.5` и `1.0`, но теперь сценарий должен быть сервер-сервер:

```text
k6 -> Additional service -> Main CRUD service -> PostgreSQL
```

То есть k6 должен бить не основной сервис `8083`, а Additional service `8084`.

### Что менять в k6

Для LAB8 можно создать отдельный файл, например:

```text
zil/k6/load-lab8-s2s.js
```

Минимальный сценарий:

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

// Имена post_ms/get_ms оставлены совместимыми с текущим plot_k6_reports.py.
const postMs = new Trend('post_ms'); // /additional/cars/availability
const getMs = new Trend('get_ms');   // /additional/stats

const baseUrl = __ENV.BASE_URL || 'http://localhost:8084';
const statsShare = Number(__ENV.STATS_SHARE || '0');
const totalVu = Number(__ENV.TARGET_VUS || '20');

export const options = {
  vus: totalVu,
  duration: __ENV.DURATION || '3m',
  summaryTrendStats: ['avg', 'p(95)', 'min', 'med', 'max'],
  thresholds: {
    http_req_failed: ['rate<0.15'],
  },
};

export default function () {
  if (Math.random() < statsShare) {
    const res = http.get(`${baseUrl}/additional/stats`);
    getMs.add(res.timings.duration);
    check(res, { ok: (r) => r.status === 200 });
  } else {
    const res = http.get(`${baseUrl}/additional/cars/availability?city=Moscow&date=2026-04-03`);
    postMs.add(res.timings.duration);
    check(res, { ok: (r) => r.status === 200 });
  }
  sleep(0.05);
}
```

Можно также адаптировать текущий `load.js`, но отдельный файл проще объяснять: LAB6 бил `/clients` и `/stats`, LAB8 бьёт Additional service, а главный запрос LAB8 проверяет доступность автомобилей по городу и дате.

Для доказательства ТЗ основной прогон лучше делать с `STATS_SHARE=0`, тогда 100% запросов идут в `/additional/cars/availability`. Смеси `5/95`, `50/50`, `95/5` ниже нужны только если преподаватель хочет график в формате LAB6 с двумя линиями.

### Запуск CPU 0.5

На прикладной ВМ:

```bash
cd ~/work/Labs_hls/zil
export APP_CPUS=0.5
export ADDITIONAL_CPUS=0.5
docker compose up -d --force-recreate
docker inspect zil-app --format '{{.HostConfig.NanoCpus}}'
docker inspect zil-additional-service --format '{{.HostConfig.NanoCpus}}'
```

На K6-ноде или на той же ВМ, если k6 установлен:

```bash
cd ~/work/Labs_hls/zil/k6
mkdir -p reports-lab8-s2s

BASE_URL=http://localhost:8084 \
TARGET_VUS=20 \
STATS_SHARE=0 \
DURATION=3m \
k6 run --summary-export reports-lab8-s2s/s2s_cpu05_availability.json load-lab8-s2s.js
```

Если k6 запускается на отдельной K6-ноде, `BASE_URL` должен указывать на адрес прикладной ВМ и порт Additional service:

```bash
BASE_URL=http://<APP_VM_INTERNAL_HOST_OR_IP>:8084 \
TARGET_VUS=20 \
STATS_SHARE=0 \
DURATION=3m \
k6 run --summary-export reports-lab8-s2s/s2s_cpu05_availability.json load-lab8-s2s.js
```

Конкретный адрес прикладной ВМ берите из таблицы курса и сетевой схемы.

### Запуск CPU 1.0

```bash
cd ~/work/Labs_hls/zil
export APP_CPUS=1.0
export ADDITIONAL_CPUS=1.0
docker compose up -d --force-recreate
docker inspect zil-app --format '{{.HostConfig.NanoCpus}}'
docker inspect zil-additional-service --format '{{.HostConfig.NanoCpus}}'
```

Затем k6:

```bash
cd ~/work/Labs_hls/zil/k6

BASE_URL=http://localhost:8084 \
TARGET_VUS=20 \
STATS_SHARE=0 \
DURATION=3m \
k6 run --summary-export reports-lab8-s2s/s2s_cpu10_availability.json load-lab8-s2s.js
```

### Если нужно несколько смесей как в LAB6

В LAB6 были смеси `5/95`, `50/50`, `95/5`. Для LAB8 можно сделать аналог:

| Файл | CPU | Смысл |
| --- | --- | --- |
| `s2s_cpu05_mix05.json` | 0.5 | 5% `/additional/stats`, 95% `/additional/cars/availability` |
| `s2s_cpu05_mix50.json` | 0.5 | 50% / 50% |
| `s2s_cpu05_mix95.json` | 0.5 | 95% `/additional/stats`, 5% `/additional/cars/availability` |
| `s2s_cpu10_mix05.json` | 1.0 | 5% / 95% |
| `s2s_cpu10_mix50.json` | 1.0 | 50% / 50% |
| `s2s_cpu10_mix95.json` | 1.0 | 95% / 5% |

Команды:

```bash
BASE_URL=http://localhost:8084 TARGET_VUS=20 STATS_SHARE=0.05 DURATION=3m k6 run --summary-export reports-lab8-s2s/s2s_cpu05_mix05.json load-lab8-s2s.js
BASE_URL=http://localhost:8084 TARGET_VUS=20 STATS_SHARE=0.50 DURATION=3m k6 run --summary-export reports-lab8-s2s/s2s_cpu05_mix50.json load-lab8-s2s.js
BASE_URL=http://localhost:8084 TARGET_VUS=20 STATS_SHARE=0.95 DURATION=3m k6 run --summary-export reports-lab8-s2s/s2s_cpu05_mix95.json load-lab8-s2s.js
```

Для CPU `1.0` меняете лимиты контейнеров и имена файлов на `cpu10`.

### Графики

Текущий `plot_k6_reports.py` ищет метрики `post_ms` и `get_ms`. Поэтому в примере `load-lab8-s2s.js` выше они оставлены с такими же именами:

```text
post_ms = /additional/cars/availability
get_ms = /additional/stats
```

Но текущий LAB6-скрипт ожидает четыре CPU (`0.5`, `1.0`, `1.5`, `2.0`), а в LAB8 нужны только `0.5` и `1.0`. Чтобы не делать лишние прогоны, создайте копию скрипта под LAB8:

```bash
cd ~/work/Labs_hls/zil/k6
cp plot_k6_reports.py plot_lab8_reports.py
nano plot_lab8_reports.py
```

В копии замените:

```python
CPU_STEPS: tuple[float, ...] = (0.5, 1.0, 1.5, 2.0)
```

на:

```python
CPU_STEPS: tuple[float, ...] = (0.5, 1.0)
```

И, если хотите красивую подпись, замените labels графика:

```python
label="POST /clients (post_ms avg)"
label="GET /stats (get_ms avg)"
plt.xlabel("Смесь POST/GET")
plt.title(f"LAB6: POST vs GET, CPU = {cpu:g} (k6 Trend, avg)")
```

на:

```python
label="/additional/cars/availability (post_ms avg)"
label="/additional/stats (get_ms avg)"
plt.xlabel("Смесь availability/stats")
plt.title(f"LAB8: Additional service, CPU = {cpu:g} (k6 Trend, avg)")
```

После этого команда будет рабочей именно для набора LAB8 `0.5` и `1.0`:

```bash
cd ~/work/Labs_hls/zil/k6
python3 plot_lab8_reports.py --lab6 reports-lab8-s2s
```

В отчёт LAB8 нужны графики для `0.5` и `1.0`.

---

## 18. Проверки перед нагрузкой

Перед каждым прогоном:

```bash
docker compose ps
curl -s http://localhost:8083/stats
curl -s http://localhost:8084/additional/stats
curl -s "http://localhost:8084/additional/cars/availability?city=Moscow&date=2026-04-03" | head
docker inspect zil-app --format '{{.HostConfig.NanoCpus}}'
docker inspect zil-additional-service --format '{{.HostConfig.NanoCpus}}'
```

Если Additional service возвращает ошибку, нагрузку запускать рано. Сначала смотрите:

```bash
docker compose logs additional --tail 120
docker compose logs app --tail 120
```

---

## 19. Что говорить на защите

Короткое объяснение:

```text
В LAB8 я вынес дополнительную логику секции "Дополнительно" в отдельный микросервис.
Основной сервис остался CRUD-сервисом и работает с PostgreSQL.
Additional service не подключается к БД, а вызывает основной сервис по REST.
Для проверки доступности он получает cars и rents, фильтрует машины по городу, находит аренды на выбранную дату, строит Set занятых carId и формирует ответ в Java.
Docker-образ Additional service собран и опубликован отдельно.
Оба сервиса запускаются одним docker-compose, CPU лимиты задаются явно через cpus.
Нагрузочные графики сняты через k6 по цепочке k6 -> Additional service -> Main service.
```

Что показать:

- Git-репозиторий Additional service;
- ссылку в колонке `Additional service` в Google Sheet;
- Docker-образ в Harbor;
- `docker compose ps`;
- `docker inspect` с `NanoCpus`;
- `curl /additional/cars/availability?city=Moscow&date=2026-04-03`;
- графики CPU `0.5` и `1.0`;
- код, где Additional service вызывает основной сервис через `RestTemplate`;
- код, где Additional service получает `/cars` и `/rents`, строит `Set<UUID>` занятых автомобилей и считает доступность в Java.

---

## 20. Типичные ошибки

### Additional service ходит на `localhost:8083`

В Docker это почти всегда ошибка. Внутри контейнера `localhost` — это сам контейнер. Нужно:

```yaml
MAIN_SERVICE_BASE_URL: http://app:8083
```

### `depends_on` есть, но сервис всё равно не готов

`depends_on` гарантирует порядок старта контейнеров, но не гарантирует, что Spring Boot уже поднялся. Если Additional service стартует быстрее основного, первые HTTP-вызовы могут падать.

Что сделать:

- добавить retry в клиенте;
- проверять `/additional/health` после старта;
- смотреть логи;
- при необходимости перезапустить Additional service:

```bash
docker compose restart additional
```

### Join случайно делается в БД

Если Additional service подключается к PostgreSQL или использует SQL/JPA, это противоречит смыслу задания. В Additional service не должно быть:

```properties
spring.datasource.url=...
```

И не должно быть JPA repository.

Если в основном сервисе уже есть `/rents/availability` или `/rents/availability/count` через `CarRepository`/JPQL, его можно оставить, но для LAB8 нужно показать новую реализацию в Additional service. На защите важно сказать, что Additional endpoint не использует этот JPQL-запрос, а сам получает `/cars` и `/rents` по REST.

### Не опубликован отдельный Docker-образ

ТЗ требует отдельный Docker-образ. Даже если сервис можно собрать локально через `build:`, для сдачи нужно показать `docker push` в Harbor и использовать image в compose.

### CPU-лимит не применился

Проверяйте не глазами в compose, а через Docker:

```bash
docker inspect zil-app --format '{{.HostConfig.NanoCpus}}'
docker inspect zil-additional-service --format '{{.HostConfig.NanoCpus}}'
```

Если видите `0`, лимит не применился. Используйте `cpus`, а не только `deploy.resources`, потому что `deploy` часто не применяется в обычном `docker compose up` без Swarm.

### k6 бьёт основной сервис, а не Additional service

Для LAB8 `BASE_URL` должен быть портом Additional service:

```bash
BASE_URL=http://localhost:8084
```

И endpoint'ы должны быть `/additional/...`.

### Пароли попали в Git

Проверьте перед push:

```bash
git status
git diff
```

Секреты должны быть в `.env` на сервере или в переменных окружения, а не в репозитории.

---

## 21. Чеклист сдачи LAB8

- Создан отдельный Git-репозиторий Additional service.
- Ссылка на него внесена в колонку `Additional service` в таблице ресурсов.
- Additional service собирается и запускается отдельно от основного сервиса.
- Собран отдельный Docker-образ Additional service.
- Docker-образ опубликован в Harbor/registry.
- Основной CRUD-сервис запускается и отдаёт `/cars`, `/clients`, `/rents`.
- Additional service вызывает основной сервис через `RestTemplate`, `WebClient` в синхронном режиме или Feign.
- Выбранный клиент можно показать в коде. Рекомендованный вариант для проекта — `RestTemplate`.
- Additional service не подключается напрямую к PostgreSQL.
- Java-side join/filter выполняется в Additional service: `/cars` + `/rents`, фильтр по `city`, пересечение аренды с `date`, `Set<UUID>` занятых `carId`.
- Есть главный endpoint `GET /additional/cars/availability?city=Moscow&date=2026-04-03`.
- `/additional/rents/details` можно оставить как опциональный демо-endpoint, но не как основной результат LAB8.
- Два сервиса запускаются одним `docker-compose.yml` или через compose + override.
- CPU для сервисов задан явно: `0.5` или `1.0`.
- Через `docker inspect` показано, что CPU-лимит применился.
- Сняты графики сервер-сервер как в LAB6 для CPU `0.5` и `1.0`.
- В отчёте указано, что нагрузка идёт по цепочке `k6 -> Additional service -> Main CRUD service`.
- В репозиториях нет реальных паролей, `.env`, приватных ключей и токенов.

---

## 22. Мини-шпаргалка команд

Вход на прикладную ВМ:

```powershell
ssh -p 2307 hl@hlssh.zil.digital
```

Swagger основного сервиса через Windows-туннель:

```powershell
ssh -p 2307 -L 8080:127.0.0.1:8083 hl@hlssh.zil.digital
```

Открыть:

```text
http://localhost:8080/swagger-ui/index.html
```

Swagger Additional service через Windows-туннель:

```powershell
ssh -p 2307 -L 8084:127.0.0.1:8084 hl@hlssh.zil.digital
```

Запуск двух сервисов:

```bash
cd ~/work/Labs_hls/zil
export APP_CPUS=1.0
export ADDITIONAL_CPUS=1.0
docker compose up --build -d
docker compose ps
```

Проверка:

```bash
curl http://localhost:8083/stats
curl http://localhost:8084/additional/stats
curl "http://localhost:8084/additional/cars/availability?city=Moscow&date=2026-04-03"
```

CPU `0.5`:

```bash
export APP_CPUS=0.5
export ADDITIONAL_CPUS=0.5
docker compose up -d --force-recreate
docker inspect zil-app --format '{{.HostConfig.NanoCpus}}'
docker inspect zil-additional-service --format '{{.HostConfig.NanoCpus}}'
```

CPU `1.0`:

```bash
export APP_CPUS=1.0
export ADDITIONAL_CPUS=1.0
docker compose up -d --force-recreate
docker inspect zil-app --format '{{.HostConfig.NanoCpus}}'
docker inspect zil-additional-service --format '{{.HostConfig.NanoCpus}}'
```

Публикация образа:

```bash
docker build -t <HARBOR_HOST>:<HARBOR_PORT>/<PROJECT>/zil-additional-service:lab8 .
docker login <HARBOR_HOST>:<HARBOR_PORT>
docker push <HARBOR_HOST>:<HARBOR_PORT>/<PROJECT>/zil-additional-service:lab8
```

Главный прогон LAB8 только по availability:

```bash
cd ~/work/Labs_hls/zil/k6
mkdir -p reports-lab8-s2s
BASE_URL=http://localhost:8084 TARGET_VUS=20 STATS_SHARE=0 DURATION=3m k6 run --summary-export reports-lab8-s2s/s2s_cpu10_availability.json load-lab8-s2s.js
```

График LAB8 в формате LAB6 со смесью availability/stats:

```bash
cd ~/work/Labs_hls/zil/k6
BASE_URL=http://localhost:8084 TARGET_VUS=20 STATS_SHARE=0.50 DURATION=3m k6 run --summary-export reports-lab8-s2s/s2s_cpu10_mix50.json load-lab8-s2s.js
```

Главное правило LAB8: **Additional service — отдельный сервис и отдельный образ; данные он получает по REST из основного CRUD-сервиса; join выполняет Java-код Additional service, а не SQL в базе.**
