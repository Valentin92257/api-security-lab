# Архитектура и эксплуатация

Нормативные интерфейсы: [реестр контрактов](../contracts/README.md). Документ описывает целевую реализацию; готовность сервисов проверяется отдельно.

## Компоненты

| Приложение | Ответственность | Доступ к данным |
|---|---|---|
| shop-api × 2 | Auth, каталог, корзина, checkout и предметные проверки | Своя shop DB; отправка итоговых access-логов Detector |
| lab-gateway | Два фиксированных входа, JWT, schema/login policies, допуск запросов | Локальные лимиты; отправка итоговых access-логов Detector |
| lab-detector | Хранение access-логов, анализ и итоговые инциденты | Только собственное хранилище; без эталона и shop/control DB |
| lab-control | UI, испытания, Runner, подготовка БД, evaluator и отчёты | Своё состояние, fixture-доступ к shop DB и readonly данные для evaluator |

```text
operator -> control [Runner + Fixture + Evaluator]
                 -> gateway:8081 -> shop-vulnerable -> shop_vulnerable DB
                 -> gateway:8082 -> shop-protected  -> shop_protected DB
                       gateway / shop -> Detector API -> detector storage
control -> Runtime API shop/gateway
control -> Detector API -> итоговые incidents
control: план + attempts + readonly shop state + incidents -> общий report
```

Один Gateway направляет порт 8081 в vulnerable, 8082 в protected. Host, query и клиентские заголовки не меняют backend. Прогоны выполняются последовательно: vulnerable, затем protected. Конкурентность допустима внутри одного прогона.

## Стек и структура

Java 25, Maven Wrapper, Spring Boot 4, Spring MVC, PostgreSQL, Spring JDBC/Flyway; локальный limiter в одном Gateway. Для UI предусмотрен Thymeleaf, для технической наблюдаемости - OpenTelemetry/LGTM. Версии генератора и уже подключённых зависимостей фиксируются в pom.xml. Остальные библиотеки и контейнеры проверяются при реализации.

```text
apps/{shop-api,lab-gateway,lab-detector,lab-control}/pom.xml
apps/<модуль>/target/generated-sources/<контракт>/src/main/java/
contracts/{shop-api,control-api,detector-api,runtime-api}.openapi.yaml
contracts/schemas/*.yaml
contracts/examples/*.json
docs/
```

Интерфейсы и DTO генерируются из OpenAPI в соответствующий модуль. Генерация не создаёт реализацию endpoint. Runner, Fixture и Evaluator - модули Control; отдельные HTTP-сервисы для них не нужны. [Внутренние порты](../contracts/modules.md).

## Данные и полномочия

Два Shop используют независимые бизнес-БД с одинаковым набором таблиц. Один PostgreSQL может также хранить данные Control и Detector при раздельных владельцах и правах. Общего SQL-интерфейса передачи логов или команд между ними нет; внутренние таблицы не фиксируются OpenAPI.

- Shop имеет DML своей БД, без DDL/TRUNCATE; каталог читается ограниченным datasource.
- Fixture-модуль Control готовит БД напрямую при закрытых runtimes. Отдельного API наполнения БД нет.
- Evaluator использует readonly бизнес-состояние без password_hash и приватный исходный snapshot.
- Detector хранит принятые логи и инциденты у себя. Его runtime-доступ не включает фикстуры, plan/labels, ответы Runner, режимы и shop DB.
- Токены Shop/Gateway в Detector разрешают запись только своего producer. Токен Control - подготовку, завершение анализа и чтение.
- Миграционная роль отделена от обычных приложений. SQL магазина: [data/README.md](data/README.md).

## Сети и доверенные заголовки

Сценарный HTTP проходит Control → Gateway → Shop. Control отдельно обращается к служебным Runtime/Detector API и БД для Fixture/Evaluator. Shop/Gateway имеют сетевой доступ к Detector API. Открываются только UI/Grafana на loopback; ручной вход Gateway возможен отдельной локальной конфигурацией. Backend, БД и служебные API наружу не публикуются.

X-Request-Id создаёт Runner до отправки, Gateway сохраняет его в Shop и ответе. Каждый retry получает новый UUID. X-Forwarded-For содержит один IP виртуального клиента: Gateway доверяет ему только от генератора, Shop - только от Gateway. Этот IP используется в логах и rate limiter. Доверенные адреса задаются конфигурацией развёртывания. X-Lab-заголовков нет; runId берётся из активного Runtime-контекста.

Служебный Bearer, operator Bearer и JWT пользователя разделены. Runtime API не проксируется через бизнес-вход. ScenarioHttpClient разрешает только фиксированные входы Gateway и бизнес-маршруты, без произвольного origin, proxy и redirects. Внешний egress контейнера Control закрывается и проверяется отдельно; Docker socket и privileged-режим не требуются. У модулей одна JVM: ограничения зависимостей Runner являются границей доверенного кода, а не отдельным сетевым sandbox.

## Исполнение, доставка и завершение

Control атомарно резервирует один слот испытания. Runner работает в отдельном executor с конечными concurrency, timeout и deadline; UI не ждёт его окончания в servlet-потоке. AttemptSink сохраняет результаты до completion Runner. Потеря UI-вкладки не останавливает серверную работу.

Каждый producer создаёт одну итоговую access-запись на запрос. Доставка в Detector - HTTP/JSON LogBatch с producer и entries; ACK подтверждает хранение. Сетевые retries сохраняют sequence и содержимое. Реализация очереди/буфера локальна producer; Control не читает файлы логов. Технические логи могут дополнительно уходить в LGTM.

Control закрывает Gateway и ждёт CLOSED, затем закрывает Shop. CLOSED означает inFlight=0 и окончательный lastSequence. Эти границы передаются Detector через finish; Detector проверяет непрерывные 1..N обоих producers и возвращает COMPLETED после анализа. Control сверяет observedRequestIds с attempts, выполняет evaluator и сохраняет report. После этого возможна новая подготовка данных.

Перезапуск instanceId, потеря записи или deadline дают INCONCLUSIVE. Client timeout не доказывает остановку SQL. При cleanupSafe=false сброс данных и новый запуск запрещены. Бизнес-commit не откатывается из-за последующей ошибки логирования. Resume старого опыта не предусмотрен.

## Запуск

Целевая локальная конфигурация: пять Java-процессов, PostgreSQL и LGTM. Compose, миграции, bootstrap ключей и проверки готовности реализуются отдельно; команды запуска не считаются работающими до появления соответствующих файлов. Секреты генерируются локально, в Git попадают только placeholders.

Ограничиваются размеры HTTP/логов, число steps, concurrency, очередь доставки, память limiter, DB pool и длительность. Принятые лимиты и параметры правил фиксируются перед измерением. Числовые требования к производительности подтверждаются измерениями, а не самим описанием.
