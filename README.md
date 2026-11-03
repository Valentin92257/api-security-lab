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

Это каркас: health проверяет процесс, бизнес-ручек, маршрутизации, защиты, UI и detector
пока нет. PostgreSQL/LGTM добавятся с реализацией соответствующих компонентов.
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
