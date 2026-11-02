# API Security Lab

Java 25 / Spring Boot 4.0.8. Четыре Maven-модуля: `apps/shop-api`, `apps/lab-gateway`,
`apps/lab-detector`, `apps/lab-control`. Runner - пакет `lab.control.runner` внутри control.
Shop запускается в двух контейнерах с профилями `vulnerable` и `protected`.

## Запуск

Нужен Docker с Compose. Скопируй `.env.example` в `.env`: `cp .env.example .env`.
Замени три `replace-with-*` на случайные секреты (`openssl rand -hex 32`) и запусти стенд:

```sh
docker compose build
docker compose up -d --wait
docker compose exec lab-control curl --fail http://127.0.0.1:8080/health
```

Локальные Maven и JDK не нужны: сборка и модульные тесты выполняются в Docker.
Control опубликован на `127.0.0.1:8080` (`LAB_CONTROL_PORT`), остальные сервисы доступны внутри Docker.
Внутренние сети могут мешать доступу с хоста, поэтому примеры используют `docker compose exec`.
Health Control проверяет БД; в Shop/Gateway/Detector пока есть только каркас и health.
Java-контейнеры работают без root с read-only файловой системой и временными файлами в `/tmp`.
Состояние и логи: `docker compose ps`, `docker compose logs --tail=100`.

## БД

PostgreSQL 17 хранит `lab_control`, `vulnerable` и `protected` в volume `control-data`.
`docker compose down` сохраняет данные, `docker compose down --volumes` удаляет их.

Перед запуском Control создаются БД и применяются миграции из `db/control/migrations`.
Приложение использует DML-роль `control_app`, без DDL и административного пароля.
`POSTGRES_USER` по умолчанию равен `lab_admin` и должен совпадать с администратором существующего volume.
Изменение `.env` не меняет созданные роли и пароли. Применённые миграции не редактируй; следующие добавляй как V3, V4 и далее.

Обновление БД без удаления данных:

```sh
docker compose stop lab-control
docker compose run --rm db-init
docker compose run --rm lab-control-migrate
docker compose up -d --wait lab-control
```

## Создание эксперимента

Control предоставляет каталог сценариев, создание и чтение trial по [контракту](contracts/control-api.openapi.yaml).
Нужен Operator Bearer из `LAB_OPERATOR_TOKEN`. Пример запускается в shell контейнера, где задана эта переменная:

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

Новый trial возвращается с 202, повтор с тем же ключом и параметрами - с 200.
Другие параметры с прежним ключом или новый ключ при занятом слоте дают 409.
Правила повторов - в [контракте](contracts/semantics.md#создание-trial).
Ошибки возвращаются как `application/problem+json`.

Пока можно создать только один trial: он остаётся PREPARING с нулевыми счётчиками и `cleanupSafe=false`.
Исполнение сценариев, отмена и безопасная финализация ещё не подключены.
После рестарта trial становится INCONCLUSIVE; ключ и занятый слот сохраняются, новый запуск заблокирован.
Отмена известного trial возвращает 503, отчёт - 409, неизвестный trial - 404.

Для интеграционных тестов нужны Java 25, Maven и Docker:

```sh
mvn --batch-mode --no-transfer-progress -pl apps/lab-control -am verify
```

Тесты используют PostgreSQL 17 через Testcontainers; Docker-сборка выполняет `package` без интеграционных тестов.

[Документация](docs/README.md) | [Архитектура](docs/01-architecture.md) | [ADR runner](docs/adr/0001-runner-inside-control.md)

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
