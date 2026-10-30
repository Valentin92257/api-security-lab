# API Security Lab

Java 25 / Spring Boot 4.0.8. Четыре Maven-модуля: `apps/shop-api`, `apps/lab-gateway`,
`apps/lab-detector`, `apps/lab-control`. Runner - пакет `lab.control.runner` внутри control.
Shop запускается в двух контейнерах с профилями `vulnerable` и `protected`.

## Запуск

Нужен только Docker с Compose. Из корня проекта:

```sh
docker compose build
docker compose up -d --wait
curl --fail http://127.0.0.1:8080/health
```

Сначала build создаёт общий образ shop для обоих контейнеров. Maven и JDK работают
в build-stage Dockerfile, тесты выполняются при сборке. Локальные Maven/JDK не нужны. `pom.xml` остаются конфигурацией сборки внутри Docker.
Образ сборки - [Maven 3.9.11 / Temurin 25](https://hub.docker.com/_/maven).
Runtime всех сервисов - `eclipse-temurin:25-jre-alpine`; `curl` для healthcheck устанавливается через `apk`.

```sh
docker compose ps
docker compose logs --tail=100
docker compose exec lab-detector curl --fail http://127.0.0.1:8080/health
docker compose down
```

Только control опубликован на `127.0.0.1:8080`; порт задаётся через `LAB_CONTROL_PORT`
(пример `.env.example`). Остальные сервисы доступны в Docker-сетях. У каждого сервиса
есть `/health` и Docker healthcheck; gateway слушает 8081 и 8082.

Реализованы health, прокси 8082 → shop-protected и сквозные ID/access-логи. Бизнес-ручек,
защитных проверок, Runtime API, UI и detector пока нет. PostgreSQL/LGTM добавятся позже.
Контейнеры работают без root; файловая система read-only, временные файлы - в `/tmp`.

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
