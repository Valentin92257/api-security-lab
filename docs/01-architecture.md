# Архитектура и эксплуатация

## Компоненты

Стенд состоит из четырёх Java-приложений. `shop-api` запускается дважды: vulnerable и protected имеют один нормативный HTTP-контракт и независимые данные. Четыре участника отвечают за API/данные, защиту, генератор и обнаружение/UI соответственно.

| Приложение | Ответственность | Доступ к данным |
|---|---|---|
| `shop-api` × 2 | Auth, каталог, корзина, checkout, предметные проверки, структурированные JSONL-логи | Собственная shop DB; запись собственного security-log файла |
| `lab-gateway` | Маршрутизация, JWT, политики, допуск запросов, аудит | Локальное состояние лимитов и запись собственного security-log файла |
| `lab-detector` | Правила, окна, evidence и инциденты | Обезличенная проекция событий, собственное состояние и задания |
| `lab-control` | Эксперименты, scenario-runner, сборщик логов, фикстуры, evaluator, UI и отчёты | Lab DB, readonly shop state, административный API подготовки данных |

```text
Оператор -> control [scenario-runner] -> gateway:8081 -> shop-vulnerable -> shop_vulnerable DB
                            \-> gateway:8082 -> shop-protected  -> shop_protected DB
gateway / shop -> JSONL files -> log-ingestor (control) -> audit -> safe view -> detector
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
| Лимиты | Bucket4j in-memory в одном gateway; локальное потокобезопасное состояние с ограничением ключей и TTL |
| Контракты | OpenAPI 3.1.0, JSON Schema, общие Java records/enums |
| UI/наблюдаемость | Thymeleaf; OpenTelemetry и локальный Grafana LGTM |
| Проверки | JUnit, Testcontainers с PostgreSQL, jqwik для последовательностей операций |

Точные patch-версии, локальный limiter, OpenAPI validator и digest образов фиксируются после сборочного spike. Проверяются required/type/bounds/additionalProperties, nullable, local refs, query и повторное чтение body. Контракт использует один относительный server `/api/v1`. Непроверенная поддержка отдельных OpenAPI keywords не принимается за готовую валидацию; `latest` в сборке и Compose не используется.

```text
apps/{shop-api,lab-gateway,lab-detector,lab-control}/
modules/scenario-runner/  # библиотека Р3, подключается только к lab-control
libs/{lab-contracts,lab-observability,lab-test-support}/
contracts/{shop-api,control-api,lab-admin-api}.yaml
contracts/{security-event,incident,experiment}.schema.json
config/{policies,rules,scenarios}/
db/{shop/common,shop/protected,lab}/
fixtures/
infra/compose.yaml
tests/acceptance/
docs/
pom.xml
```

Приложения не зависят от классов друг друга. Общие библиотеки содержат wire contracts и технические соглашения, но не business/evaluator logic. Detector не подключает fixtures и scenario catalog. Уязвимые адаптеры изолированы в пакете `lab.vulnerable` и выбираются конфигурацией запуска, а не запросом клиента.

## Встроенное исполнение сценариев

`modules/scenario-runner` — библиотека с plan/scenarios/execution и портами `ScenarioHttpClient`, `AttemptSink`; единственное приложение-потребитель — lab-control. Р3 владеет библиотекой и её тестами, Р4 — orchestration/UI/evaluator и адаптером сохранения результатов. Роли команды сохраняются.

Orchestrator вызывает `prepare(plan)`, затем `start(prepared, cancellationToken, attemptSink)` и получает completion handle. `cancel()` прекращает новые шаги и отменяет ожидания клиента; после завершения всех задач формируется step manifest. HTTP-запрос оператора возвращает 202 сразу после принятия запуска. Отдельных runner HTTP endpoints, worker listener, heartbeat и lease нет.

На MVP разрешён один активный run. Единственный экземпляр control атомарно резервирует слот исполнения до PREPARING; конкурентный start получает 409. Слот освобождается только после завершения и подтверждения cleanupSafe; INCONCLUSIVE с cleanupSafe=false блокирует новый запуск до безопасной подготовки. Runner использует отдельный executor, semaphore для concurrency, ограниченные очереди и размеры body/results, deadline всего плана и timeout запроса. Он не занимает servlet-потоки UI, общий ForkJoinPool и executor сборщика логов. AttemptSink сохраняет bounded batches в control.attempts; при ошибке или переполнении новые шаги прекращаются. Final manifest фиксируется только после сохранения всех результатов. Cancel/deadline проверяются также перед каждой попыткой и retry; отмена клиентского Future не доказывает завершения SQL в shop.

У модулей общая JVM, heap и CPU: отдельный executor снижает взаимное влияние, но не изолирует OOM/GC/падения. При отказе control опыт INCONCLUSIVE без resume. Run deadline действует независимо от открытой вкладки UI; логирование и управление остановкой имеют отдельные рабочие ресурсы. Межмодульные зависимости проверяются ArchUnit/правилами сборки, сценарии поставляются только доверенным кодом репозитория.

## Данные и полномочия

Один PostgreSQL обслуживает три БД: `shop_vulnerable`, `shop_protected`, `lab`. В lab разделены схемы `audit` (потоки логов и разобранные события), `control` (runs, steps, labels, oracle, reports) и `detection` (jobs, markers, rule state, incidents). Это логическая изоляция; отказ общего сервера затрагивает все три БД.

| Роль | Разрешённый доступ |
|---|---|
| `shop_*_app` | DML собственной DB; без DDL/TRUNCATE |
| `catalog_reader_*` | Только SELECT каталога; отдельный datasource, включая уязвимый адаптер |
| `lab_log_ingestor` | Модуль control: чтение зарегистрированных JSONL, добавление audit events и продвижение log cursor; без изменения исходных событий |
| `detector_app` | SELECT безопасной view; свои jobs/state/incidents/markers |
| `control_app` | Control schema, raw audit, чтение incidents, запись detector jobs |
| `evaluator_reader_*` | Readonly бизнес-состояние без password_hash |
| `fixture_admin_*` | Подготовка своей DB при закрытом admission и правильном epoch |
| `migration_owner` | DDL в init job; credential отсутствует в обычных приложениях |

Источник события определяется зарегистрированным файлом и process instance ID, а не входным HTTP-заголовком. Gateway/shop не имеют SQL-credentials lab DB. Collector — модуль lab-control с отдельной SQL-ролью lab_log_ingestor. Detector не читает файлы, raw audit, control и shop DB: ему доступна только whitelist-view. Запреты проверяются под runtime SQL-ролями.

Заказ, поля идемпотентности, корзина и купон фиксируются одной SQL-транзакцией. После успешного commit API пишет JSONL-событие результата. Эта запись не атомарна с SQL; надежность при падениях исключена из MVP. Состояние политик хранится в памяти единственного gateway. Collector индексирует прочитанные логи в lab DB; broker не нужен.

## Сети и служебные интерфейсы

| Internal network | Участники | Назначение |
|---|---|---|
| `lab-traffic` | control, gateway, LGTM collector | Сценарный HTTP и telemetry |
| `shop-backend` | gateway, оба shop | Бизнес-трафик |
| `shop-admin` | control, gateway, оба shop | Подготовка, admission, завершение |
| `lab-data` | control, detector, shop, PostgreSQL | SQL; gateway к этой сети не подключается |
| `lab-observe` | control, detector, gateway, shop, LGTM | Логи, traces, metrics |

Только UI и Grafana публикуются на `127.0.0.1`. Ручной доступ к gateway возможен отдельным opt-in profile также через loopback. Backend, БД и management не публикуются. Соседи в bridge-сети могут обращаться к listener, поэтому нужны одновременно правильный bind address и проверка полномочий endpoint.

Control вызывает runner как Java-модуль, без отдельного listener. Gateway/shop management используют административный scope; `/internal/**` не проксируется через бизнес-вход. Подготовка и закрытие защищены epoch, чтобы задержанная команда не изменила следующий run.

Control не имеет внешнего egress, proxy env, Docker socket или privileged-режима. ScenarioHttpClient принимает target enum и route из каталога, а не произвольный URL; redirects/proxy отключены. Сценарный модуль не импортирует JDBC, admin/evaluator/log-reader adapters и не получает их через интерфейсы. Это архитектурная граница доверенного кода, не sandbox: JVM control имеет доступ к lab-data, shop-admin и log mounts. Нельзя утверждать, что контейнерная сеть запрещает встроенному runner доступ к этим ресурсам. Тесты отдельно проверяют сетевой запрет внешнего egress и ограничения ScenarioHttpClient на внутренние цели.

## Файлы событий и сборщик

Каждый gateway/shop получает собственный writable named volume для security-логов; control монтирует эти тома read-only. Один файл на producer/run: `<runId>/<streamId>.jsonl`. Control назначает путь, producer создаёт файл в своём writable volume; пользовательский запрос не выбирает файл. Detector не получает эти тома; сценарному модулю control не передаётся интерфейс чтения файлов, Docker socket не используется. Внутри control модуль log-ingestor читает файлы независимо от UI и сохраняет проверенные события в audit. Console/технические логи и traces могут дополнительно идти в LGTM; detector не зависит от поиска по Loki/Grafana.

## Запуск и конфигурация

Базовый Compose: пять Java-контейнеров (control, gateway, detector и два shop), PostgreSQL и LGTM — семь постоянно работающих контейнеров. Init jobs создают локальные ключи, выполняют миграции и seed. Планируемая команда запуска:

```sh
docker compose -f infra/compose.yaml up --build
```

Readiness означает готовность профильных зависимостей. Ключи JWT, operator/admin/run credentials и HMAC redaction генерируются локально; в git только `.env.example` с placeholders. Образы, versions и config snapshots фиксируются. Ограничиваются body/response, число steps/events, concurrency, очереди, DB pool, длительность и диск. Параметры калибруются пилотом и сохраняются до запуска измерения.

Цель cold start — готовность за 10 минут на документированной машине с указанной сетью. Измерение включает загрузки; тёплый запуск записывается отдельно. Команда и целевое время описывают требуемую реализацию, а не уже выполненный benchmark.

## Допущение о сбоях

MVP измеряет защиту при непрерывной работе процессов и хранилищ. Восстановление прерванного эксперимента не реализуется. Падение/перезапуск компонента, ошибка записи/разбора лога или нарушение протокола доставки делает run INCONCLUSIVE. Выполненные бизнес-транзакции при этом не отменяются. После сбоя стенд перезапускают, фикстуры пересоздают и запускают новый run; старый не продолжается. Незавершенные runs при старте control помечаются INCONCLUSIVE.

Нормальные конкурентные запросы, задержки обработки, отказы бизнес-правил и HTTP-timeout при живом сервере остаются частью испытаний. Для них сохраняются транзакции, идемпотентность, ожидание завершения in-flight операций и обработка всех логов. Отсутствие падений не означает синхронной доставки последнего события.

## Завершение и очистка

Штатный порядок: stop runner → close admission gateway/shop → дождаться in-flight=0 в живых процессах → flush и STREAM_END в каждом файле → collector DRAINED → detector CLOSE/ACK → readonly oracle и отчет → reset. Счетчик in-flight уменьшается после записи terminal log. PROCESS instance ID и health проверяются до/после ожидания: после перезапуска счетчик не используется как доказательство завершения старого run.

До завершения отчета логи не ротируются/не обрезаются. Ошибка записи, незавершенная строка на deadline, пропуск seq, смена process ID, отсутствие STREAM_END/ACK или превышение deadline дают INCONCLUSIVE. Нельзя подменять ожидание sleep фиксированной длины. Для недоказанно завершившейся работы cleanupSafe=false; обычный reset запрещен, нужен полный перезапуск стенда и новая фикстура.

Контрольные метрики: log-ingest lag, detector lag, parse errors, seq gaps, открытые запросы и completeness. Security-логи не семплируются. Пароли, bearer, cookies и исходные idempotency keys не записываются ни в security-log, ни в HTTP-debug.

## Архитектурные решения

Отдельный detector изолирует правила от приватных labels/эталона. Control объединяет UI, runner, сборщик логов и evaluator, чтобы не добавлять приложение ради доставки. Shop остаётся одним кодом в двух профилях; gateway применяет внешние политики, shop — предметные ограничения. SQL обеспечивает корректность бизнес-эффекта, JSONL — наблюдаемость. Мы сознательно не обещаем сохранность события при падении между commit и записью лога.

Правильность детектора оценивает независимый evaluator по плану, response evidence и readonly состоянию. HTTP-отказ или incident не равны результату атаки. Единый контракт логирования — требование к подключаемому API; без него внутренние бизнес-факты недоступны детектору.

## Исходники модели данных

В docs/data находятся начальные SQL, DBML/Mermaid и подробный ER HTML. SQL задаёт физическую схему; DBML, ER и словари полей документов 02/04 поддерживаются вместе с ним. В shop 7 таблиц, в lab 12; число физических таблиц трех баз — 26 (7×2+12). Protected constraints применяются только к защищённой shop DB. Python-генератора нет.

Начальные SQL не подменяют транзакционный Java-код, проверку источников логов и bootstrap всех runtime-ролей. В docs/api-security-lab.html сохраняются 12 концептуальных схем; data/er.html показывает 8 подробных фрагментов реальной модели.
