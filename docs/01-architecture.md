# Архитектура и эксплуатация

## Компоненты

Стенд состоит из пяти Java-приложений. `shop-api` запускается дважды: vulnerable и protected имеют один нормативный HTTP-контракт и независимые данные. Четыре участника отвечают за API/данные, защиту, генератор и обнаружение/UI соответственно.

| Приложение | Ответственность | Доступ к данным |
|---|---|---|
| `shop-api` × 2 | Auth, каталог, корзина, checkout, предметные проверки, audit outbox | Собственная shop DB; отдельная роль доставки событий |
| `lab-gateway` | Маршрутизация, JWT, политики, допуск запросов, аудит | Redis и запись собственных событий в lab DB |
| `scenario-runner` | Исполнение сценариев и сбор результатов HTTP-attempts | Без SQL/Redis; задания и результаты через control API |
| `lab-detector` | Правила, окна, evidence и инциденты | Обезличенная проекция событий, собственное состояние и задания |
| `lab-control` | Эксперименты, фикстуры, независимый evaluator, UI и отчёты | Lab DB, readonly shop state, административный API подготовки данных |

```text
Оператор -> control -> runner -> gateway:8081 -> shop-vulnerable -> shop_vulnerable DB
                            \-> gateway:8082 -> shop-protected  -> shop_protected DB
gateway / shop outbox -> lab DB / audit -> detector view -> detector -> incidents
control / evaluator  -> steps + audit + readonly shop state + incidents -> report
все приложения      -> локальные логи, метрики и traces
```

Gateway выбирает backend по локальному входному порту. Пользовательские Host, query и заголовки не меняют цель. Оператор управляет стендом через control; runner использует только встроенные targets.

## Стек и структура

| Область | Выбор и обоснование |
|---|---|
| Язык/сборка | Java 25, Maven Wrapper, multi-module; ограниченный I/O на virtual threads |
| Приложения | Spring Boot 4.0.x, Spring Cloud 2025.1.x, Gateway Server Web MVC 5.0.x; единая servlet-модель |
| Хранение | PostgreSQL 17, Spring JDBC/JdbcClient, Flyway; явные SQL и транзакции |
| Лимиты | Bucket4j и Redis; готовый механизм распределённых счётчиков |
| Контракты | OpenAPI 3.1.0, JSON Schema, общие Java records/enums |
| UI/наблюдаемость | Thymeleaf; OpenTelemetry и локальный Grafana LGTM |
| Проверки | JUnit, Testcontainers с PostgreSQL, jqwik для последовательностей операций |

Точные patch-версии, Redis-адаптер, OpenAPI validator и digest образов фиксируются после сборочного spike. Проверяются required/type/bounds/additionalProperties, nullable, local refs, query и повторное чтение body. Контракт использует один относительный server `/api/v1`. Непроверенная поддержка отдельных OpenAPI keywords не принимается за готовую валидацию; `latest` в сборке и Compose не используется.

```text
apps/{shop-api,lab-gateway,scenario-runner,lab-detector,lab-control}/
libs/{lab-contracts,lab-observability,lab-test-support}/
contracts/{shop-api,control-api,runner-api,lab-admin-api}.yaml
contracts/{security-event,incident,experiment}.schema.json
config/{policies,rules,scenarios}/
db/{shop/common,shop/protected,lab}/
fixtures/
infra/compose.yaml
tests/acceptance/
docs/final/
pom.xml
```

Приложения не зависят от классов друг друга. Общие библиотеки содержат wire contracts и технические соглашения, но не business/evaluator logic. Detector не подключает fixtures и scenario catalog. Уязвимые адаптеры изолированы в пакете `lab.vulnerable` и выбираются конфигурацией запуска, а не запросом клиента.

## Данные и полномочия

Один PostgreSQL обслуживает три БД: `shop_vulnerable`, `shop_protected`, `lab`. В lab разделены схемы `audit` (события/receipts/manifests), `control` (runs, steps, labels, oracle, reports) и `detection` (jobs, markers, rule state, incidents). Это логическая изоляция; отказ общего сервера затрагивает все три БД.

| Роль | Разрешённый доступ |
|---|---|
| `shop_*_app` | DML собственной DB; без DDL/TRUNCATE |
| `catalog_reader_*` | Только SELECT каталога; отдельный datasource, включая уязвимый адаптер |
| `*_audit_writer` | Добавление событий собственного producer; без изменения/удаления/чтения labels |
| `detector_app` | SELECT безопасной view; свои jobs/state/incidents/markers |
| `control_app` | Control schema, raw audit, чтение incidents, запись detector jobs |
| `evaluator_reader_*` | Readonly бизнес-состояние без password_hash |
| `fixture_admin_*` | Подготовка своей DB при закрытом admission и правильном epoch |
| `migration_owner` | DDL в init job; credential отсутствует в обычных приложениях |

Producer identity проверяется SQL-ролью/ограниченной функцией вставки. Detector не имеет SELECT на raw audit, control и shop DB. Эти запреты проверяются реальными SQL-запросами под runtime-ролями.

Заказ, одноразовый intent, idempotency record и купон фиксируются одной SQL-транзакцией. Redis содержит только оперативное состояние политик. События доставляются через outbox с повторами и дедупликацией; отдельный broker не требуется для масштаба локального стенда.

## Сети и служебные интерфейсы

| Internal network | Участники | Назначение |
|---|---|---|
| `lab-traffic` | runner, gateway, LGTM collector | Сценарный HTTP и telemetry |
| `worker-control` | runner, control | Задания, heartbeat, результаты |
| `shop-backend` | gateway, оба shop | Бизнес-трафик |
| `shop-admin` | control, gateway, оба shop | Подготовка, admission, завершение |
| `lab-data` | control, detector, gateway, shop, PostgreSQL, Redis | SQL и состояние политик |
| `lab-observe` | control, detector, gateway, shop, LGTM | Логи, traces, metrics |

Только UI и Grafana публикуются на `127.0.0.1`. Ручной доступ к gateway возможен отдельным opt-in profile также через loopback. Backend, БД и management не публикуются. Соседи в bridge-сети могут обращаться к listener, поэтому нужны одновременно правильный bind address и проверка полномочий endpoint.

Control имеет отдельный worker listener: credential runner разрешает только назначенный job, без operator/reset/proxy-функций. Gateway/shop management используют другой scope; `/internal/**` не проксируется через бизнес-вход. Подготовка и закрытие защищены epoch, чтобы задержанная команда не изменила следующий run.

Runner не имеет внешней/default сети, proxy env, Docker socket, host mounts или privileged-режима. Allowlist клиента дополняется запретом egress; redirects отключены. Проверяются обращения к внешним IP/hostname, host gateway, direct shop, SQL/Redis и management, включая попытки обхода через DNS. Разрешены только gateway и узкий worker API.

## Запуск и конфигурация

Базовый Compose: шесть Java-контейнеров, PostgreSQL, Redis и LGTM — девять постоянно работающих контейнеров. Init jobs создают локальные ключи, выполняют миграции и seed. Планируемая команда запуска:

```sh
docker compose -f infra/compose.yaml up --build
```

Readiness означает готовность профильных зависимостей. Ключи JWT, operator/worker/admin credentials и HMAC redaction генерируются локально; в git только `.env.example` с placeholders. Образы, versions и config snapshots фиксируются. Ограничиваются body/response, число steps/events, concurrency, очереди, DB pool, длительность и диск. Параметры калибруются пилотом и сохраняются до запуска измерения.

Цель cold start — готовность за 10 минут на документированной машине с указанной сетью. Измерение включает загрузки; тёплый запуск записывается отдельно. Команда и целевое время описывают требуемую реализацию, а не уже выполненный benchmark.

## Сбои и восстановление

| Сбой | Поведение |
|---|---|
| Detector/UI недоступен | API работает при доступном audit; detector догоняет поток, report ждёт закрытия окон |
| Control недоступен | Runner останавливает новые шаги после потери lease; recovery сверяет незавершённые работы |
| Redis недоступен | ENFORCE rate policy: 503/INFRA_REJECT; OBSERVE: ERROR проверки, измерение политики неполно |
| Lab DB недоступна | Gateway не вызывает новый downstream без durable receipt; уже начатые shop commits сохраняют outbox, если собственная DB доступна |
| Shop DB недоступна | 503; до reconciliation новый reset запрещён |
| Crash после shop commit | Order/key/outbox восстановимы; retry того же key возвращает прежний результат |
| Gateway crash после downstream | Receipt, shop ledger и состояние определяют исход; отсутствие ответа не доказывает rollback |
| LGTM недоступен | Ограниченный buffer/drop counter; audit продолжает работать, потеря traces отмечается отдельно |
| Backlog/диск исчерпан | Закрыть admission; не удалять необработанные доказательства |
| Завершение операций не доказано | INCONCLUSIVE, cleanupSafe=false; перед reset остановить runtime и проверить DB-сессии |

Штатный reset: остановка новых шагов → закрытие admission → отсутствие незавершённых операций → сохранение отчёта → фикстуры. Удаление volume не является способом получить достоверный результат опыта.

Логи имеют JSON-формат, service/request/trace ids и безопасный reason. Traces связывают runner → gateway → shop и асинхронный detector. Наблюдаются latency/errors, policy time, outbox/detector lag, receipts, lease expiry и coverage. Пароли, bearer и исходные idempotency keys не попадают в логи; audit хранится независимо от telemetry sampling.

## Архитектурные решения

Отдельный detector обеспечивает SQL-изоляцию labels и самостоятельное восстановление; UI и evaluator объединены в control для уменьшения числа сервисов. Один shop codebase с двумя профилями удерживает контракт общим. JDBC делает транзакционную защиту проверяемой; owner-check выполняется там, где доступен владелец объекта. SQL outbox исключает потерю события успешного commit. Версионированные конфигурации и последовательные paired runs делают сравнение воспроизводимым. Качество защиты устанавливает независимый evaluator, поэтому HTTP-отказ и incident сами по себе не являются результатом эксперимента.
