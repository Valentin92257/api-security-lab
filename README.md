# API Security Lab

Базовый каркас локального стенда на Java 25, Spring Boot 4.0.8 и Maven 3.9.11.
Сейчас приложения предоставляют только `GET /health` → `200 {"status":"UP"}`.
Маршрутизация, магазин, политики, detector, runner и UI пока не реализованы.
Профили shop выбирают будущие реализации; сами по себе пока не включают защиту.

## Структура

```text
pom.xml                         Maven reactor
apps/shop-api/                  Spring Boot; vulnerable/protected instances
apps/lab-gateway/               Spring Boot; listeners 8081 and 8082
apps/lab-detector/              Spring Boot
apps/lab-control/               Spring Boot; future UI/orchestration
modules/scenario-runner/        library used only by lab-control
infra/compose.yaml              five application containers
infra/docker/HealthProbe.java   JRE-only Docker healthcheck
```

В каждом приложении: `pom.xml`, `Dockerfile`, `src/main/java`,
`src/main/resources`, `src/test/java`. Приложения не зависят друг от друга.
Spring Cloud и JDBC/Flyway будут добавлены вместе с соответствующей функциональностью.
PostgreSQL и LGTM описаны в целевой архитектуре, но пока не требуются для health-каркаса.

## Локальная сборка

Нужен JDK 25. POSIX `mvnw` загружает официальный Maven Wrapper 3.3.4 и Maven 3.9.11
в `~/.m2`; первый запуск требует сети и curl/wget. Windows: WSL либо установленный Maven 3.9.11.

```sh
./mvnw -B verify
java -jar apps/lab-control/target/app.jar
curl --fail http://127.0.0.1:8080/health
```

Остальные приложения запускаются аналогично. При одновременном запуске вне Docker
задайте разные `SERVER_PORT`; gateway дополнительно использует `GATEWAY_ADDITIONAL_PORT`.
Shop: `SPRING_PROFILES_ACTIVE=protected` или `vulnerable`.

## Docker Compose

Из корня репозитория; установленный локально JDK/Maven не нужен:

```sh
docker compose -f infra/compose.yaml build
docker compose -f infra/compose.yaml up -d --wait
curl --fail http://127.0.0.1:8080/health
docker compose -f infra/compose.yaml ps
```

Один образ shop используется двумя контейнерами; сначала выполняется build, затем up.
Опубликован только control на loopback. Порт можно изменить через `LAB_CONTROL_PORT`;
пример находится в `.env.example`. У каждого контейнера свой Docker healthcheck.
Проверить внутренний сервис без публикации его порта:

```sh
docker compose -f infra/compose.yaml exec lab-detector java -cp /opt/health HealthProbe
docker compose -f infra/compose.yaml logs --tail=100
docker compose -f infra/compose.yaml down
```

Контейнеры работают без root, с read-only filesystem и отдельным `/tmp`.
Runtime-сети internal; загрузка зависимостей происходит только при сборке образов.
Автоматического restart нет, чтобы не скрывать сбои эксперимента.
`/health` показывает работоспособность текущего процесса, а не готовность будущего стенда к эксперименту.

## Проверки и документация

`./mvnw verify` поднимает HTTP-сервер каждого приложения на случайном порту
и проверяет реальный ответ `/health`. Dockerfile выполняет эти тесты при сборке.
Healthcheck использует JRE без curl в runtime-образе.
Базовые образы закреплены по major Java 25; digest pinning остаётся отдельным шагом перед воспроизводимой итоговой сборкой.

[Документация проекта](docs/README.md) · [Архитектура](docs/01-architecture.md)

Версии и servlet API сверены с [требованиями Spring Boot 4.0](https://docs.spring.io/spring-boot/4.0/system-requirements.html) и [Tomcat factory API](https://docs.spring.io/spring-boot/4.0/api/java/org/springframework/boot/tomcat/servlet/TomcatServletWebServerFactory.html).
