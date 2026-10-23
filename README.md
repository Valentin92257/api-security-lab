# API Security Lab

Java 25 / Spring Boot 4.0.8. Четыре Maven-модуля: `apps/shop-api`, `apps/lab-gateway`,
`apps/lab-detector`, `apps/lab-control`. Runner — пакет `lab.control.runner` внутри control.
Shop запускается в двух контейнерах с профилями `vulnerable` и `protected`.

## Запуск

Нужен только Docker с Compose. Из корня проекта:

```sh
docker compose build
docker compose up -d --wait
curl --fail http://127.0.0.1:8080/health
```

Сначала build создаёт общий образ shop для обоих контейнеров. Maven и JDK работают
только в build-stage Dockerfile, тесты выполняются при сборке. Локальные Maven/JDK
и Maven Wrapper не нужны. `pom.xml` остаются конфигурацией сборки внутри Docker.
Образ сборки — [Maven 3.9.11 / Temurin 25](https://hub.docker.com/_/maven).

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
Контейнеры работают без root; файловая система read-only, временные файлы — в `/tmp`.

[Документация](docs/README.md) · [Архитектура](docs/01-architecture.md)
