# API Security Lab

Java 25 / Spring Boot 4.0.8. Maven-модули: общая библиотека контрактов `libs/lab-contracts`
и четыре приложения `apps/shop-api`, `apps/lab-gateway`, `apps/lab-detector`, `apps/lab-control`.
Runner - пакет `lab.control.runner` внутри control.
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
Health Control проверяет БД. Shop содержит каркас API и миграции; Gateway проксирует protected-запросы со сквозным ID и access-логами. Detector пока предоставляет health.
Java-контейнеры работают без root с read-only файловой системой и временными файлами в `/tmp`.
Состояние и логи: `docker compose ps`, `docker compose logs --tail=100`.

## БД

PostgreSQL 17 хранит `lab_control`, `vulnerable` и `protected` в volume `control-data`.
`docker compose down` сохраняет данные, `docker compose down --volumes` удаляет их.

PostgreSQL создаёт базы данных через скрипты в `/docker-entrypoint-initdb.d/` при первом запуске с пустым volume. При старте Control Flyway применяет миграции из `db/control/migrations`, включённые в JAR, с отдельными административными credentials. Рабочие запросы используют DML-роль `control_app`. Shop также применяет миграции при старте. Отдельного контейнера миграций нет.
`POSTGRES_USER` по умолчанию равен `lab_admin` и должен совпадать с администратором существующего volume.
Изменение `.env` не меняет созданные роли и пароли. Применённые миграции не редактируй; следующие добавляй как V3, V4 и далее.

На существующем volume init-скрипты повторно не выполняются. Если базы уже созданы, дополнительных действий не нужно. Для создания недостающих баз без удаления данных:

```sh
docker compose exec -T db sh -c 'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -f /docker-entrypoint-initdb.d/20-databases.sql'
```

Новые миграции таблиц применяются при следующем запуске соответствующего приложения.

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
Настроены `Flyway` миграции в зависимости от активного Spring профиля. Настроены интеграционные тесты с использованием
`Testcontainers`. Каждый режим API обращается к собственной БД.
По умолчанию тесты запускаются без DataSource, `PostgreSQLIntegrationTest` позволяет проводить тесты с контейнером
PostgreSQL и настроенным окружением.

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
| `libs/lab-contracts` | модели `common`, `control`, `detector`, `runtime` + сериализация/валидация (`lab.contracts.json`) |
| `apps/shop-api` | `shop` (API и модели), `runtime` (API) |
| `apps/lab-gateway` | `runtime` (API) |
| `apps/lab-detector` | `detector` (API) |
| `apps/lab-control` | `control` (API) |

Пакеты `lab.contracts.<контракт>.api` содержат интерфейсы API с HTTP-аннотациями, `lab.contracts.<контракт>.model` - DTO и enum.

Общий `Problem` находится в `lab.contracts.common.model`, общий `Incident` — в `lab.contracts.detector.model`.
Работа с JSON вне контроллеров и область автоматической MVC-валидации описаны в [lab-contracts](libs/lab-contracts/README.md).

У Shop API отключена генерация Bean Validation: ограничения спецификации применяются реализацией с учётом режима. Protected отклоняет нарушения, vulnerable допускает учебные quantity/quotedTotal. Runtime и остальные API сохраняют Bean Validation.

Для компиляции замените `clean generate-sources` на `compile`.

Правки вносятся в [спецификации](contracts/README.md), код пересоздаётся при сборке и не редактируется вручную. Общие настройки OpenAPI Generator находятся в [корневом pom.xml](pom.xml), спецификации для каждого модуля - в его `pom.xml`.

Сгенерированные Java-файлы хранятся в Git. После изменения спецификаций или настроек генератора повторите генерацию и добавьте обновлённые файлы в коммит. [CI](.github/workflows/generated-code.yml) повторяет генерацию на push и pull request и завершается ошибкой при расхождении.

## Каркас gateway и корреляция

`/api/v1/**` на listener `8082` проксируется в `SHOP_PROTECTED_URL` (по умолчанию `http://shop-protected:8080`). Listener `8081` пока возвращает 404 для бизнес-запросов; vulnerable-маршрут добавится отдельной задачей. `/health` работает на обоих портах без X-Request-Id и проверяет сам процесс, а не доступность Shop.

Прокси сохраняет метод, исходный path/query, тело и прикладные заголовки, включая Authorization, Content-Type и Idempotency-Key. Ответ магазина передаётся с исходным статусом и телом; сохраняются Retry-After, WWW-Authenticate, Location, Idempotency-Replayed и повторяющиеся заголовки. Hop-by-hop заголовки (включая перечисленные в Connection) удаляются, Host/Content-Length формируются транспортом. Автоматические retries, redirects, cookie storage и распаковка ответа отключены. Gateway не валидирует JWT или бизнес-схему и не применяет login limiter.

Обязателен ровно один `X-Request-Id` в полном UUID-формате. Корректный ID передаётся в Shop и возвращается клиенту. Отсутствующий/неверный/повторяющийся заголовок даёт 400/INVALID_REQUEST без обращения к upstream; для самого отказа создаётся один UUID, общий для ответа и лога. В Shop действует тот же контракт. `/internal/**` не проксируется; служебный Runtime API пока не реализован.

Gateway и оба Shop пишут JSON-логи `http_request`: `service`, `trace_id`, `method`, `path`, `status`, `duration_ms`. Здесь `trace_id` — тот же X-Request-Id в MDC, отдельного ID не генерируется. В `path` нет query; токены, пароли, тела и заголовки не записываются. Это минимальные диагностические логи, ещё не полный AccessLog/LogBatch для Detector. Фильтр работает для синхронных `/api/v1` запросов после допуска HTTP-парсером; ошибки самого парсера до servlet-фильтров сюда не входят.

| Параметр | По умолчанию | Назначение |
|---|---|---|
| SHOP_PROTECTED_URL | http://shop-protected:8080 | Фиксированный HTTP origin, без path/query/userinfo |
| GATEWAY_CONNECT_TIMEOUT | 2s | Установление соединения |
| GATEWAY_UPSTREAM_TIMEOUT | 5s | Общий deadline обращения к upstream, включая чтение тела и ожидание соединения из пула |
| GATEWAY_MAX_BODY_BYTES | 8388608 | Максимальный буфер тела запроса/ответа; допустимо до 16 MiB |

Недоступный upstream даёт 502/UNAVAILABLE, таймаут — 504/UNAVAILABLE. Слишком большой запрос — 413/INVALID_REQUEST, ответ upstream сверх бюджета — 502/UNAVAILABLE. Все эти ответы содержат X-Request-Id и Problem.requestId. Таймаут не означает откат заказа, proxy не повторяет операцию. Бюджеты — технические ограничения транспорта, а не экспериментальные политики.

Тесты запускаются при Docker-сборке. Gateway integration tests используют локальный тестовый HTTP upstream: методы, query/body/headers, ID, ответы 429/redirect, disconnect без retry, timeout заголовков/тела, ограничения буфера и listener. Shop HTTP-тесты проверяют тот же ID в ответе/логе обоих профилей, 400/500, отсутствие секретов и восстановление MDC. Тестовые ручки существуют только в `src/test`.
