# Архитектура и эксплуатация

## Компоненты

Стенд состоит из четырёх Java-приложений. `shop-api` запускается дважды: vulnerable и protected имеют один нормативный HTTP-контракт и независимые данные. Четыре участника отвечают за API/данные, защиту, генератор и обнаружение/UI соответственно.

| Приложение | Ответственность | Доступ к данным |
|---|---|---|
| `shop-api` × 2 | Auth, каталог, корзина, checkout, предметные проверки, итоговые access-логи | Собственная shop DB; доставка своих логов Detector |
| `lab-gateway` | Маршрутизация, Runtime admission, корреляция и access-логи | Доставка своих логов Detector; без защитных политик |
| `lab-detector` | Правила, окна, evidence и инциденты | Собственные access-логи, состояние и инциденты |
| `lab-control` | Эксперименты, scenario-runner, фикстуры, evaluator, UI и отчёты | Приватное хранилище Control, readonly shop state и прямой административный доступ Fixture к БД |

```text
Оператор -> control [scenario-runner] -> gateway:8081 -> shop-vulnerable -> shop_vulnerable DB
                            \-> gateway:8082 -> shop-protected  -> shop_protected DB
gateway / shop -> HTTP/JSON LogBatch -> detector -> final incidents -> control
control / evaluator  -> plan + attempts + readonly shop state + incidents -> report
все приложения      -> локальные логи, метрики и traces
```

Gateway выбирает backend по локальному входному порту. Пользовательские Host, query и заголовки не меняют цель. Оператор управляет стендом через control; runner использует только встроенные targets.

## Стек и структура

| Область | Выбор и обоснование |
|---|---|
| Язык/сборка | Java 25, Maven в Docker, multi-module; ограниченный I/O на virtual threads |
| Приложения | Spring Boot 4.0.x, Spring Cloud 2025.1.x, Gateway Server Web MVC 5.0.x; единая servlet-модель |
| Хранение | PostgreSQL 17, Spring JDBC/JdbcClient, Flyway; явные SQL и транзакции |
| Лимиты | Bucket4j in-memory в protected Shop; локальное потокобезопасное состояние с ограничением ключей и TTL |
| Контракты | OpenAPI 3.1.0, JSON Schema, генерация Java-интерфейсов/DTO |
| UI/наблюдаемость | Thymeleaf; OpenTelemetry и локальный Grafana LGTM |
| Проверки | JUnit, Testcontainers с PostgreSQL, jqwik для последовательностей операций |

Точные patch-версии, локальный limiter, OpenAPI validator и digest образов фиксируются после сборочного spike. Проверяются required/type/bounds/additionalProperties, nullable, local refs, query и повторное чтение body. Shop API использует `/api/v1` через Gateway:8081/8082; Control - `/control/v1`, Runtime/Detector - `/internal/v1`. Непроверенная поддержка отдельных OpenAPI keywords не принимается за готовую валидацию; `latest` в сборке и Compose не используется.

```text
apps/{shop-api,lab-gateway,lab-detector,lab-control}/
apps/lab-control/src/main/java/lab/control/runner/  # пакет Р3 внутри Control
libs/{lab-contracts,lab-observability,lab-test-support}/
contracts/{shop-api,control-api,detector-api,runtime-api}.openapi.yaml
contracts/schemas/{common,shop,control,detector,runtime,logs}.yaml
config/{policies,rules,scenarios}/
db/{shop/common,shop/protected}/
fixtures/
compose.yaml
tests/acceptance/
docs/
pom.xml
```

Дерево показывает планируемую структуру; сейчас существуют четыре apps с генерацией HTTP-кода. Приложения не зависят от классов друг друга. Общие библиотеки содержат wire contracts и технические соглашения, но не business/evaluator logic. Detector не подключает fixtures и scenario catalog. Уязвимые адаптеры изолированы в пакете `lab.vulnerable` и выбираются конфигурацией запуска, а не запросом клиента.

## Встроенное исполнение сценариев

`lab.control.runner` - пакет планирования и выполнения сценариев; единственное приложение-потребитель - lab-control. Р3 владеет библиотекой и её тестами, Р4 - orchestration/UI/evaluator и адаптером сохранения результатов. Роли команды сохраняются.

Control подготавливает и запускает сценарии внутри процесса; завершение подтверждается после сохранения результатов всех начатых запросов. Отмена прекращает новые шаги и повторы. Внутренние Java-интерфейсы определяются при реализации. HTTP-запрос оператора создаёт trial и возвращает 202 сразу после принятия запуска. Отдельных runner HTTP endpoints, worker listener, heartbeat и lease нет.

На MVP разрешён один активный trial с двумя последовательными runs. Единственный экземпляр control атомарно резервирует слот исполнения до PREPARING; конкурентный start получает 409. Слот освобождается только после завершения и подтверждения cleanupSafe; INCONCLUSIVE с cleanupSafe=false блокирует новый запуск до безопасной подготовки. Runner использует отдельный executor, semaphore для concurrency, ограниченные очереди и размеры body/results, deadline всего плана и timeout запроса. Он не занимает servlet-потоки UI, общий ForkJoinPool и executor orchestration. Control сохраняет результаты во внутреннем хранилище Control; при ошибке или переполнении новые шаги прекращаются. Completion подтверждается только после сохранения всех результатов. Cancel/deadline проверяются также перед каждой попыткой и retry; отмена клиентского Future не доказывает завершения SQL в shop.

У модулей общая JVM, heap и CPU: отдельный executor снижает взаимное влияние, но не изолирует OOM/GC/падения. При отказе control опыт INCONCLUSIVE без resume. Run deadline действует независимо от открытой вкладки UI; логирование и управление остановкой имеют отдельные рабочие ресурсы. Межмодульные зависимости проверяются ArchUnit/правилами сборки, сценарии поставляются только доверенным кодом репозитория.

## Данные и полномочия

Один PostgreSQL обслуживает независимые `shop_vulnerable` и `shop_protected` и внутренние хранилища Control/Detector. Это логическая изоляция; отказ общего сервера затрагивает все данные стенда. Общая SQL-схема между Control и Detector не является контрактом.

| Роль | Разрешённый доступ |
|---|---|
| `shop_*_app` | DML собственной DB; без DDL/TRUNCATE |
| `catalog_reader_*` | Только SELECT каталога; отдельный datasource, включая уязвимый адаптер |
| `detector_app` | Собственные access-логи, state и incidents; без доступа к Control/Shop |
| `control_app` | Планы, attempts, приватный baseline и reports |
| `evaluator_reader_*` | Readonly бизнес-состояние без password_hash |
| `fixture_admin_*` | Подготовка своей DB модулем Control при закрытых runtimes |
| `migration_owner` | DDL в init job; credential отсутствует в обычных приложениях |

Shop/Gateway доставляют access-логи по HTTP с токенами своей роли, без SQL-credentials хранилища Detector. Control управляет анализом и читает итоговые incidents через Detector API. Приватный baseline и labels туда не передаются. Запреты доступа проверяются под runtime-ролями.

Заказ, поля идемпотентности, корзина и купон фиксируются одной SQL-транзакцией. Итоговый access-лог формируется после результата/обработки ошибки; он не атомарен с SQL. Надёжность при падениях исключена из MVP. Состояние политик хранится в памяти protected Shop; broker не нужен.

## Сети и служебные интерфейсы

| Internal network | Участники | Назначение |
|---|---|---|
| `lab-traffic` | control, gateway, LGTM collector | Сценарный HTTP и telemetry |
| `shop-backend` | gateway, оба shop | Бизнес-трафик |
| `shop-admin` | control, gateway, оба shop | Подготовка, admission, завершение |
| `lab-data` | control, detector, shop, PostgreSQL | SQL своих хранилищ; gateway к этой сети не подключается |
| `lab-logs` | control, detector, gateway, shop | Detector API, отдельные служебные токены |
| `lab-observe` | control, detector, gateway, shop, LGTM | Логи, traces, metrics |

Только UI и Grafana публикуются на `127.0.0.1`. Ручной доступ к gateway возможен отдельным opt-in profile также через loopback. Backend, БД и management не публикуются. Соседи в bridge-сети могут обращаться к listener, поэтому нужны одновременно правильный bind address и проверка полномочий endpoint.

Control вызывает Runner как Java-модуль, без отдельного listener. Runtime API Shop/Gateway и управление Detector используют отдельный ServiceBearer; `/internal/**` не проксируется через бизнес-вход. runId задаёт контекст, повтор одинаковой подготовки безопасен, несовместимый контекст даёт 409. БД готовит Fixture напрямую, отдельного HTTP API наполнения/reset нет.

Control не имеет внешнего egress, proxy env, Docker socket или privileged-режима. HTTP-клиент генератора принимает target enum и route из каталога, а не произвольный URL; redirects/proxy отключены. Сценарный модуль не импортирует JDBC, admin/evaluator adapters и не получает их через интерфейсы. Это архитектурная граница доверенного кода, не sandbox: JVM control имеет доступ к lab-data и shop-admin. Нельзя утверждать, что контейнерная сеть запрещает встроенному runner доступ к этим ресурсам. Тесты отдельно проверяют сетевой запрет внешнего egress и ограничения HTTP-клиента генератора на внутренние цели.

## Доставка access-логов

Gateway и Shop формируют одну итоговую запись на запрос и отправляют LogBatch непосредственно в Detector. Detector подтверждает сохранение всей пачки кодом 204, хранит данные и анализирует их у себя. Повтор producer/sequence/content безопасен; конфликт даёт 409. JSONL допустим как локальный файл, но общие тома и сборщик Control не требуются.

Console/технические логи и traces могут дополнительно идти в LGTM; Detector не зависит от поиска по Loki/Grafana. Формат, лимиты и правила завершения описаны в [документе 04](04-events-and-detection.md) и [контрактах](../contracts/README.md).

## Запуск и конфигурация

Целевая конфигурация: пять Java-контейнеров (control, gateway, detector и два shop), PostgreSQL и LGTM - семь постоянно работающих контейнеров. Сейчас Compose содержит только пять Java-контейнеров и health-checks; PostgreSQL/LGTM, init jobs, миграции и seed добавятся с бизнес-логикой. Команда запуска каркаса:

```sh
docker compose up --build
```

Readiness означает готовность профильных зависимостей. Ключи JWT, operator/service credentials и HMAC redaction генерируются локально; в git только `.env.example` с placeholders. Образы, versions и config snapshots фиксируются. Ограничиваются body/response, число steps/events, concurrency, очереди, DB pool, длительность и диск. Параметры калибруются пилотом и сохраняются до запуска измерения.

Цель cold start - готовность за 10 минут на документированной машине с указанной сетью. Измерение включает загрузки; тёплый запуск записывается отдельно. Команда и целевое время описывают требуемую реализацию, а не уже выполненный benchmark.

## Допущение о сбоях

MVP измеряет защиту при непрерывной работе процессов и хранилищ. Восстановление прерванного эксперимента не реализуется. Падение/перезапуск компонента, ошибка записи/разбора лога или нарушение протокола доставки делает run INCONCLUSIVE. Выполненные бизнес-транзакции при этом не отменяются. После сбоя стенд перезапускают, фикстуры пересоздают и запускают новый run; старый не продолжается. Незавершенные runs при старте control помечаются INCONCLUSIVE.

Нормальные конкурентные запросы, задержки обработки, отказы бизнес-правил и HTTP-timeout при живом сервере остаются частью испытаний. Для них сохраняются транзакции, идемпотентность, ожидание завершения in-flight операций и обработка всех логов. Отсутствие падений не означает синхронной доставки последнего события.

## Завершение и очистка

Штатный порядок: stop runner → сохранить attempts → закрыть Gateway и дождаться CLOSED → закрыть Shop и дождаться CLOSED → передать оба lastSequence в finishAnalysis → дождаться COMPLETED Detector → сверить observedRequestIds и получить incidents → readonly oracle и отчёт → reset. Счётчик in-flight уменьшается после фиксации итогового access-лога. instanceId проверяется до/после ожидания: после перезапуска счётчик не используется как доказательство завершения старого run.

До завершения отчёта логи и результаты не удаляются. Ошибка записи, пропуск sequence, смена instanceId, отсутствие COMPLETED или превышение deadline дают INCONCLUSIVE. Нельзя подменять ожидание sleep фиксированной длины. Для недоказанно завершившейся работы cleanupSafe=false; обычный reset запрещён, нужен полный перезапуск стенда и новая фикстура.

Контрольные метрики: log-ingest lag, detector lag, parse errors, seq gaps, открытые запросы и completeness. Security-логи не семплируются. Пароли, bearer, cookies и исходные idempotency keys не записываются ни в security-log, ни в HTTP-debug.

## Архитектурные решения

Отдельный detector изолирует правила от приватных labels/эталона. Control объединяет UI, runner, Fixture и evaluator, чтобы не добавлять приложение ради доставки. Shop остаётся одним кодом в двух профилях; gateway маршрутизирует запросы без защитных политик, shop выполняет JWT, schema/login и предметные проверки. SQL обеспечивает корректность бизнес-эффекта, access-логи - наблюдаемость. Мы сознательно не обещаем сохранность события при падении между commit и записью лога.

Правильность детектора оценивает независимый evaluator по плану, response evidence и readonly состоянию. HTTP-отказ или incident не равны результату атаки. Единый контракт логирования - требование к подключаемому API; он передаёт только HTTP-наблюдения; внутренние бизнес-факты проверяет Evaluator.

## Исходники модели данных

В docs/data находятся начальные SQL магазина, DBML/Mermaid и подробный ER HTML. SQL задаёт физическую схему; DBML, ER и словарь полей документа 02 поддерживаются вместе с ним. В каждой shop DB семь таблиц; protected constraints применяются только к защищённой DB. Прежняя общая lab-схема сохранена в [архиве](data/legacy-lab/README.md), а не является текущим протоколом хранения Control/Detector. Python-генератора нет.


Начальные SQL не подменяют транзакционный Java-код, проверку источников логов и bootstrap всех runtime-ролей. В docs/api-security-lab.html сохраняются 12 концептуальных схем; data/er.html показывает shop-фрагменты и отдельно помеченные архивные lab-фрагменты.

## Существующий каркас и генерируемые интерфейсы

Один образ shop-api запускается с профилями protected/vulnerable. Они выбирают lab.shop.secured и lab.shop.vulnerable соответственно; без профиля или с обоими запуск отклоняется. ShopImplementation.mode() обозначает выбранный вариант, не готовую бизнес-логику. Actuator предоставляет /health; существующие тесты проверяют оба режима и неверные конфигурации.

HTTP-контроллеры Shop при реализации используют lab.contracts.shop.api.DefaultApi и DTO lab.contracts.shop.model, делегируя бизнес-операции в application. Runtime, Detector и Control аналогично используют свои сгенерированные интерфейсы. Сейчас бизнес-контроллеров нет; Spring Boot-сборка уже генерирует, компилирует и включает эти классы в app.jar.
