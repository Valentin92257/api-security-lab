# API Security Lab

Java 25 / Spring Boot 4.0.8. Четыре Maven-модуля: `apps/shop-api`, `apps/lab-gateway`,
`apps/lab-detector`, `apps/lab-control`. Runner - пакет `lab.control.runner` внутри control.
Shop запускается в двух контейнерах с профилями `vulnerable` и `protected`.

## Запуск

Нужен только Docker с Compose. Из корня проекта создай `.env`:

```sh
cp .env.example .env
```

Замени три значения `replace-with-*` на случайные секреты.
Каждый можно получить командой `openssl rand -hex 32`. Затем запусти стенд:

```sh
docker compose build
docker compose up -d --wait
docker compose exec lab-control curl --fail http://127.0.0.1:8080/health
```

Сначала build создаёт общий образ shop для обоих контейнеров. Maven и JDK работают
в build-stage Dockerfile, тесты выполняются при сборке. Локальные Maven/JDK не нужны. `pom.xml` остаются конфигурацией сборки внутри Docker.
Образ сборки - [Maven 3.9.11 / Temurin 25](https://hub.docker.com/_/maven).
Runtime Java-приложений - `eclipse-temurin:25-jre-alpine`; `curl` для healthcheck устанавливается через `apk`.

```sh
docker compose ps
docker compose logs --tail=100
docker compose exec lab-detector curl --fail http://127.0.0.1:8080/health
docker compose down
```

Только для control настроена публикация на `127.0.0.1:8080`; порт задаётся через `LAB_CONTROL_PORT`
(пример `.env.example`). Остальные сервисы доступны в Docker-сетях. У каждого сервиса
есть `/health` и Docker healthcheck; gateway слушает 8081 и 8082.

На Docker Engine 29.5.2 порт контейнера, подключённого только к `internal`-сетям,
может быть недоступен с host. Поэтому команды здесь выполняются через
`docker compose exec`. Подробнее: [Moby #53256](https://github.com/moby/moby/discussions/53256).

Control создаёт и читает эксперименты в PostgreSQL; его health проверяет БД.
Shop/Gateway/Detector пока предоставляют каркас и health. Маршрутизация, защита,
исполнение атак, UI, анализ Detector, LGTM и бизнес-миграции Shop ещё не подключены.
Java-контейнеры работают без root; файловая система read-only, временные файлы - в `/tmp`.

Один сервис `db` с PostgreSQL 17 хранит БД `lab_control`, `vulnerable` и `protected`
в существующем volume `control-data`. БД магазина подготовлены для его миграций.
Обычный `docker compose down`
сохраняет данные, `docker compose down --volumes` удаляет их. PostgreSQL доступен
только внутри Docker-сети.

При переходе с прежнего сервиса `postgres` сначала выполни
`docker compose down --remove-orphans`, затем `docker compose up --build -d --wait`.
Это останавливает старый сервер перед подключением того же volume к `db` и сохраняет данные.

В БД две таблицы: `trials` с параметрами, состоянием и ключом создания эксперимента
и `execution_slot` с единственным слотом. Они сохраняют историю и идемпотентность
после рестарта, а транзакция не позволяет двум запросам одновременно занять слот.

`db-init` после готовности PostgreSQL создаёт отсутствующие БД и роль `control_app`.
Он безопасен для повторного запуска и работает с уже заполненным volume.
Он напрямую запускает `psql` с `db/init/bootstrap.sql`; общая подготовка роли
находится в `db/control/role.sql` и используется также интеграционными тестами.
Затем `lab-control-migrate` запускает официальный образ Flyway перед Control и завершается.
Flyway создаёт схему `control` и применяет SQL из `db/control/migrations`.
HTTP-приложение использует роль `control_app` с DML-правами на свои таблицы,
без DDL и доступа к истории Flyway. Административный пароль ему не передаётся.
Общие ограничения контейнеров находятся в `compose.common.yaml`; credentials передаются
сервисам явно. `POSTGRES_USER` по умолчанию равен `lab_admin` и должен совпадать
с администратором существующего volume. Изменение `.env` не меняет уже созданные роли и пароли.

Начальные таблицы находятся в `V1__trials.sql`, права runtime-роли - в `V2__runtime_permissions.sql`.
Для следующего изменения БД добавь `V3__name.sql`, затем `V4__name.sql` и так далее.
Таблицы, индексы и права меняются в миграциях. Уже применённые файлы не редактируются.
Обновить существующую БД без удаления данных:

```sh
docker compose stop lab-control
docker compose run --rm db-init
docker compose run --rm lab-control-migrate
docker compose up -d --wait lab-control
```

## Создание эксперимента

[ADR runner](docs/adr/0001-runner-inside-control.md).
Реализованы `GET /control/v1/scenarios`, `POST /control/v1/trials`,
`GET /control/v1/trials` и `GET /control/v1/trials/{trialId}`.
Для доступа нужен Operator Bearer из `LAB_OPERATOR_TOKEN`.
Пример выполняется в shell контейнера Control, где эта переменная уже задана.

```sh
docker compose exec lab-control sh
trial_key=$(cat /proc/sys/kernel/random/uuid)
curl --fail-with-body -i http://127.0.0.1:8080/control/v1/trials \
  -H "Authorization: Bearer $LAB_OPERATOR_TOKEN" \
  -H "Idempotency-Key: $trial_key" \
  -H 'Content-Type: application/json' \
  --data '{"families":["BOLA","REPLAY"],"seed":42}'
curl --fail-with-body http://127.0.0.1:8080/control/v1/trials \
  -H "Authorization: Bearer $LAB_OPERATOR_TOKEN"
exit
```

Новый trial возвращает 202 и PREPARING. Повтор с тем же ключом и параметрами - 200
с тем же trial. Другие параметры с прежним ключом или новый ключ при занятом слоте
дают 409. Порядок families и JSON-полей не влияет на повтор. Если seed не передан,
Control выбирает его один раз. Отсутствие seed и явное значение считаются разными запросами.

Ошибки возвращаются как `application/problem+json`. Тело запроса ограничено 8 KiB.
Неизвестные и повторные поля, дубликаты families, неверные типы и `seed: null` дают 400.
Целый seed можно записать как `42`, `42.0` или `4.2e1`.

Пока реализовано только создание: trial остаётся PREPARING с нулевыми счётчиками
и `cleanupSafe=false`. После рестарта Control он становится INCONCLUSIVE.
История и ключ сохраняются, слот остаётся занят. Освобождение слота появится вместе
с безопасной финализацией; сейчас можно проверить один trial и его повторы.
Отмена известного trial возвращает 503, чтение отчёта - 409, неизвестный trial - 404.

Для интеграционных тестов нужны Java 25, Maven и Docker. Тесты запускают
PostgreSQL 17 через Testcontainers:

```sh
mvn --batch-mode --no-transfer-progress -pl apps/lab-control -am verify
```

Общая конвенция имён — `*IntegrationTest`: Surefire исключает их, Failsafe запускает
на фазах `integration-test` и `verify`. Отдельный профиль не нужен. Docker-сборка
выполняет `package`, поэтому запускает health-тесты и не требует Docker daemon внутри build.
Они проверяют создание и чтение trial, валидацию, идемпотентность, конкурирующие
запросы, rollback, восстановление прерванного trial при реальном перезапуске Control
и права runtime-роли. Контейнером PostgreSQL управляет JUnit; роль и миграции
читаются из test classpath, приложение подключается как `control_app`.

[Документация](docs/README.md) · [Архитектура](docs/01-architecture.md)

## Реализации магазина

Один образ shop-api запускается в двух контейнерах. Профиль `protected` выбирает
`lab.shop.secured`, профиль `vulnerable` - `lab.shop.vulnerable`.
Название пакета `secured` используется потому, что `protected` - ключевое слово Java.

```text
lab.shop
├── api           общие контроллеры; DTO из lab.contracts.shop.model
├── application   интерфейсы операций
├── secured       защищённые реализации
└── vulnerable    намеренно уязвимые реализации
```

Без профиля магазина или с обоими профилями запуск завершается ошибкой.
Дополнительные профили, например `test`, допустимы. Клиент не выбирает реализацию.
HTTP-контроллеры реализуют lab.contracts.shop.api.DefaultApi и делегируют операции в application; secured/vulnerable не вызывают друг друга.
Проверки владельца, SQL, checkout и идемпотентность реализуются отдельно в каждом варианте.
Сейчас созданы структура, выбор варианта и проверки запуска; бизнес-операций ещё нет.
`ShopImplementation.mode()` обозначает вариант, а не наличие работающей защиты.
`/health` предоставляет Actuator; тесты охватывают оба профиля и неверные конфигурации.

## Генерация кода из OpenAPI

[Единые спецификации](contracts/README.md) используются при Docker-сборке и в CI. Сгенерировать Java-интерфейсы API и DTO из корня проекта в том же образе Maven:

```sh
docker run --rm -v "$PWD:/workspace" -w /workspace \
  maven:3.9.11-eclipse-temurin-25 \
  mvn --batch-mode --no-transfer-progress clean generate-sources
```

Код генерируется в `target/generated-sources/<контракт>/src/main/java` соответствующего модуля:

| Модуль | Контракты |
|---|---|
| `apps/shop-api` | `shop`, `runtime` |
| `apps/lab-gateway` | `runtime` |
| `apps/lab-detector` | `detector` |
| `apps/lab-control` | `control` |

Пакеты `lab.contracts.<контракт>.api` содержат интерфейсы API с HTTP-аннотациями, `lab.contracts.<контракт>.model` - DTO и enum.

У Shop API отключена генерация Bean Validation: ограничения спецификации применяются реализацией с учётом режима. Protected отклоняет нарушения, vulnerable допускает учебные quantity/quotedTotal. Runtime и остальные API сохраняют Bean Validation.

Для компиляции замените `clean generate-sources` на `compile`.

Правки вносятся в [спецификации](contracts/README.md), код пересоздаётся при сборке и не редактируется вручную. Общие настройки OpenAPI Generator находятся в [корневом pom.xml](pom.xml), спецификации для каждого модуля - в его `pom.xml`.

Сгенерированные Java-файлы хранятся в Git. После изменения спецификаций или настроек генератора повторите генерацию и добавьте обновлённые файлы в коммит. [CI](.github/workflows/generated-code.yml) повторяет генерацию на push и pull request и завершается ошибкой при расхождении.
