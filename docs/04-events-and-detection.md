# Логирование, обнаружение и дашборд

## Как detector получает информацию

Единственный источник наблюдений detector — структурированные security-логи gateway и обоих API. Каждый producer пишет JSONL в собственный файл; log-ingestor внутри lab-control читает эти файлы, проверяет формат/источник/порядок и индексирует события в audit.security_events. Detector периодически читает whitelist-view audit.detector_observations. Он не обращается к бизнес-БД, raw файлам, сценарию или эталону.

Это инструментированный API: интеграция требует выполнения контракта логирования ниже. Детектор не разбирает произвольные текстовые сообщения и не получает от приложения готовый диагноз «атака успешна». Он получает наблюдаемые факты. Операционные console-логи, метрики и traces дополнительно идут в LGTM, но не являются входом алгоритма обнаружения.

## Граница MVP

Процессы и хранилища считаются непрерывно работающими в течение run. Восстановление после падения не реализуется. Сбой, перезапуск, ошибка записи/парсинга или нарушение протокола делает run INCONCLUSIVE; опыт повторяют с новым runId после перезапуска стенда и подготовки фикстур. При старте control прежние незавершенные runs не продолжаются.

Между бизнес-commit и записью лога нет атомарности. Это принятое ограничение, а не гарантия отсутствия потерь при падении. Нормальная задержка чтения/анализа остается возможной, поэтому завершение требует STREAM_END и detector ACK. Нельзя считать отсутствие инцидента доказанным до завершения потока.

## Требования к логированию gateway и API

1. UTF-8 JSONL: одна JSON-запись на физическую строку с LF. Переводы строк внутри значений экранируются. Никаких multiline stack traces, ANSI, текстовых prefixes и произвольного raw body в security-файле.
2. Отдельный файл на producer/run: `<runId>/<streamId>.jsonl`. Control назначает путь и streamId; HTTP-ввод не управляет путем. Каждый producer монтирует собственный том на запись, control — на чтение. Файл не ротируется и не обрезается до сохранения/экспорта отчета.
3. До OPEN producer сообщает случайный sourceInstanceId, созданный один раз при старте процесса. Collector связывает file/stream/run/partition/source с регистрацией в audit.log_streams. Поля строки сверяются с ней, а не принимаются как доказательство источника.
4. Запись выполняется одним упорядочивающим writer: присвоение seq, сериализация, append полной строки и flush буфера сериализованы. Seq начинается с 1, увеличивается на 1 на EVENT, общий для всех HTTP-потоков producer в этом run. Асинхронное отбрасывание сообщений и sampling запрещены. Flush не объявляется fsync-гарантией при аварии.
5. Request start пишется до обработки. Результат mutation с stateChanged=true — только после подтвержденного commit; rollback/refusal дает stateChanged=false. Read/auth факты записываются после получения результата. Terminal request.completed — последняя запись данного запроса до уменьшения in-flight и HTTP-ответа. Ошибка/timeout клиента не доказывает rollback сервера.
6. Обе реализации API используют один наблюдающий код и одинаковые поля. Политика защиты может различаться, формат наблюдений — нет. Неизвестное значение — null, не false. Подготовительные fixture/admin операции не попадают в экспериментальный поток.
7. Пароли, bearer/cookies, исходные idempotency keys, полный query/body и произвольный user-agent не записываются. Для корреляции применяются HMAC/hash; resource IDs — синтетические. HTTP-debug также редактируется. Поля имеют whitelist и ограничения длины.
8. Максимальный размер одной строки 64 KiB UTF-8; максимум 100000 EVENT суммарно на run. Control до OPEN распределяет квоты между gateway/shop streams; их сумма не больше 100000. Producers получают свои квоты в PREPARE, collector дополнительно проверяет общий бюджет. Лимиты плана не могут быть выше. Превышение не приводит к усечению: producer закрывает admission, сообщает unhealthy/error, опыт недействителен. Ошибка логирования после commit не отменяет уже созданный заказ.
9. После CLOSE admission и in-flight=0 producer записывает STREAM_END и flush. После этого в данном файле нет новых EVENT. Поздние отклоненные запросы старого run пишутся только в отдельный operational log. Повтор управляющей команды не создает второй маркер.

## Формат JSONL v1

EVENT содержит версию transport-формата, привязку к процессу, seq и CloudEvents structured JSON. EventId создается один раз; повторное чтение той же строки не создает нового события. JSONL-файл содержит только EVENT и STREAM_END.

```json
{"logVersion":1,"recordKind":"EVENT","streamId":"f599b2a5-b117-4a4a-82c2-df77d3452da9","sourceInstanceId":"57fdcbe3-d208-4ca0-b813-f04edf6d0acb","seq":3,"event":{"specversion":"1.0","id":"a327de28-e16c-49c7-bcb2-42b33a7ca739","source":"urn:lab:shop:vulnerable","type":"lab.object.accessed.v1","time":"2026-10-22T10:00:00.123Z","datacontenttype":"application/json","dataschema":"urn:lab:schema:security-event:1","data":{"schemaVersion":1,"context":{"runId":"42c28083-c1c9-407b-b1dd-7f58fab42e90","partitionId":"98ceecbb-5f06-40cd-bd39-205fc687381a","streamId":"f599b2a5-b117-4a4a-82c2-df77d3452da9","requestId":"e42fb0db-45dc-4b1a-8157-66c3a815e450","traceId":"4bf92f3577b34da6a3ce929d0e0e4736"},"actor":{"kind":"USER","id":"7","authenticated":true},"request":{"method":"GET","route":"/api/v1/orders/{orderId}","sourceKey":"src-01","sizeBytes":0},"action":{"kind":"READ","resourceType":"ORDER","resourceId":"123"},"outcome":{"status":"SUCCESS","httpStatus":200,"reasonCode":"OK","durationMs":12},"observations":{"subjectMatchesOwner":false,"operationExecuted":true,"stateChanged":false}}}}
```

Пример — отдельная строка внутри потока, а не весь поток. В доверенном harness requestId равен attemptId, созданному runner до отправки и проверенному gateway по run credential/context. Вне harness requestId создаёт gateway; произвольный клиентский header не является доверенным. Actor у стартового события ANONYMOUS до проверки JWT; непроверенный sub не записывается как подтвержденный actor. Envelope допускает optional subject, остальные приведенные envelope/context поля обязательны. Action optional; resourceId может быть null. Terminal outcome: SUCCESS/REJECTED/ERROR, httpStatus integer/null, reasonCode enum, durationMs≥0. У start outcome отсутствует. Event time — реальное UTC наблюдения; бизнес-время заказа не подменяет его.

После всех событий producer пишет отдельную управляющую строку, не SecurityEvent и не вход правил:

```json
{"logVersion":1,"recordKind":"STREAM_END","streamId":"f599b2a5-b117-4a4a-82c2-df77d3452da9","sourceInstanceId":"57fdcbe3-d208-4ca0-b813-f04edf6d0acb","lastSeq":9,"emittedAt":"2026-10-22T10:00:03Z"}
```

lastSeq — последний номер EVENT; для пустого потока 0. До маркера должны присутствовать ровно EVENT 1…lastSeq без пропусков. Маркер не получает seq, не входит в count/hash набора detector и не сообщает labels или результаты сценария.

## Наблюдаемые события

| Type | Producer | Обязательный смысл |
|---|---|---|
| lab.http.request.started.v1 | Gateway | Один start принятого experiment HTTP-attempt до проверок |
| lab.http.request.completed.v1 | Gateway | Один terminal исход; observations.downstreamInvoked boolean обязателен |
| lab.api.request.started.v1 | Shop | Один start принятого gateway request |
| lab.api.request.completed.v1 | Shop | Один terminal исход после всех бизнес-событий и commit/rollback |
| lab.policy.evaluated.v1 | Gateway | Policy id/version/mode, evaluationStatus, wouldDecision, reasonCode, enforcedAction |
| lab.auth.attempted.v1 | Shop | authOutcome SUCCESS/FAILURE и accountKey HMAC |
| lab.object.accessed.v1 | Shop | resource и subjectMatchesOwner boolean/null |
| lab.operation.completed.v1 | Shop | kind, operationExecuted/stateChanged, intentHash, cartKey/revision, orderId, idempotencyOutcome |
| lab.input.observed.v1 | Shop | Разрешенные validationCategories без raw payload |

Допустимые facts (также `downstreamInvoked` для проверки полноты gateway terminal): subjectMatchesOwner, operationExecuted, stateChanged, quantityWithinBounds, quoteMatchesServer; couponEligibility VALID/EXPIRED/EXHAUSTED/NONE/UNKNOWN; idempotencyOutcome NEW/REPLAYED/CONFLICT/NONE; authOutcome SUCCESS/FAILURE; accountKey, intentHash, idempotencyKeyHash, cartKey/cartRevision, orderId. Категории ввода: SQL_LIKE, TYPE_MISMATCH, UNKNOWN_FIELD, QUANTITY_OUT_OF_RANGE. Произвольные maps не принимаются. SQL_LIKE означает признак ввода, а не успешную injection.

Rules фильтруют конкретные типы: например, auth-failure-burst считает lab.auth.attempted.v1, duplicate-checkout-effect — lab.operation.completed.v1 с kind=CHECKOUT и stateChanged=true. Universal start/terminal не дают повторного счета тех же auth/checkout фактов. Cached retry пишет REPLAYED/stateChanged=false после завершения read-транзакции и не считается новым эффектом.

## Collector и права

Log-ingestor — модуль lab-control, не отдельное Java-приложение. Он читает только зарегистрированные файлы из read-only томов; не использует Docker socket. Читатель хранит файловую позицию в памяти, частичную последнюю строку ждет до LF. После STREAM_END неполная строка или новая запись — нарушение. Отсутствие маркера до deadline — INCONCLUSIVE. Восстановления чтения старого run после перезапуска нет.

Для каждой полной EVENT строки проверяются схема, размер, источник/sourceInstanceId, run/partition/stream, UUID и следующий seq. Canonical event payload hash вычисляет collector. В одной SQL-транзакции он вставляет audit.security_events и увеличивает last_ingested_seq. Идентичное повторное чтение уже принятого id/seq/payload допустимо без продвижения; иной payload или конфликт seq — ошибка. Нельзя просто пропускать невалидные строки и продолжать успешный отчет.

STREAM_END сохраняет expected_last_seq/end_seen_at. State=DRAINED допустим только при полном непрерывном диапазоне и отсутствии ошибок. В audit.log_gaps проверяются состояние потоков, counts, пары start/terminal и наличие результата shop для downstreamInvoked=true. Нулевые gaps дополняются сверкой request IDs с runner results, проверкой process instance/health и in-flight. Протокол обнаруживает видимые пропуски, но не доказывает корректность самого instrumentation: это проверяется интеграционными тестами.

Gateway и shop не имеют credentials lab DB. Collector использует lab_log_ingestor: SELECT зарегистрированных streams/events, INSERT events и UPDATE прогресса streams, без доступа к private control. Control регистрирует stream и читает raw audit для evaluator. Detector имеет только audit.detector_observations и собственные таблицы. Runtime-role не владеет view/таблицами и не наследует migration/admin.

Whitelist-view с security_barrier=true выполняется с правами владельца, без security_invoker=true. В ней нет runId, stream/seq/source URI/process ID, target, scenario/case/step/episode, labels, policy mode/hash, oracle и raw JSON. Producer нормализован до GATEWAY/SHOP. Request/trace/event IDs нужны для join/evidence, partition для изоляции; YAML не использует их как литеральные признаки атаки. Unknown boolean сохраняется null.

## Правила и обработка detector

Detector — отдельный процесс, не блокирует HTTP. YAML допускает match, countWindow и именованные Java kinds для последовательностей; только whitelist полей, eq/in, count/countDistinct, bounded window/groupBy и фиксированный шаблон evidence. SpEL, SQL и произвольный код запрещены.

```yaml
id: auth-failure-burst
version: 1
kind: countWindow
eventType: lab.auth.attempted.v1
where:
  - field: observations.authOutcome
    op: eq
    value: FAILURE
groupBy: [request.sourceKey]
window: PT60S
aggregate:
  function: countDistinct
  field: requestId
threshold: 10
classification:
  category: AUTHENTICATION_ABUSE
  technique: CREDENTIAL_STUFFING
severity: MEDIUM
```

Порог примера калибруется до измерений. PREPARE валидирует полный ruleset snapshot, engine version, versions/hash, лимиты окон/групп/evidence. Unknown field/operator/schema запрещают RUNNING; hot reload нет. Начальные правила: owner-mismatch, auth-failure-burst, login-failure-then-success, sql-like-input, invalid-business-input, duplicate-checkout-effect, checkout-replay-conflict и релевантные причины блокировок gateway.

Один worker на partition внутри единственного процесса detector. DB lease/fencing и failover не применяются. Выборка — anti-join к processed_events(partition,rulesetHash,eventId), чтобы не пропустить поздний commit между двумя потоками. State, incident/evidence/history и processed marker фиксируются одной транзакцией; marker получают и события без срабатывания. Повторное чтение не дублирует incident.

Окна event-time, равные timestamps упорядочиваются eventId. Поздние события пересчитывают затронутые bounded windows. Live analysisState=PROVISIONAL. После обоих STREAM_END и DRAINED control создает CLOSE с полным безопасным набором event UUID/hash/count. Detector проверяет наличие/обработку всех UUID, закрывает окна, получает FINAL/RETRACTED и ACK. Только FINAL используются в метриках. Ошибка или превышение лимита не маскируется усечением.

## Инцидент, оценка и UI

Incident v1: incidentId/partitionId, category AUTHENTICATION_ABUSE/ACCESS_CONTROL/REPLAY/INJECTION/BUSINESS_ABUSE, technique/tags, severity LOW/MEDIUM/HIGH, confidenceLevel UNKNOWN/LOW/MEDIUM/HIGH, confidenceScore=null в MVP; ruleId/version, rulesetHash, detectorVersion, method RULE/CORRELATION, detectedAt/createdAt/updatedAt; analysisState PROVISIONAL/FINAL/RETRACTED; operator status OPEN/ACKNOWLEDGED/RESOLVED и revision; безопасное summary, resource refs, evidence event/request IDs. Impact detector только SUSPECTED/UNKNOWN.

Уникальность incident: partition/ruleset/rule/correlationKey/windowKey; для match используется causal key, windowKey не NULL. evidence_event_ids — множество UUID; history — append-only {revision,analysisState,status,actor,comment:null/string,resolution:null/string,changedAt,summary}. Создание требует history revision 0; каждое изменение — CAS revision+1 и одну запись. Source events проверяются через safe view. Evidence сохраняется также при RETRACTED. Operator status не меняет автоматический verdict.

Control отдельно сопоставляет FINAL incidents с attack-specific steps/episodes и независимым oracle. Report содержит TP/FP/UNASSESSED и confirmedImpact=CONFIRMED/NOT_CONFIRMED/UNKNOWN. FN хранится в результате episode без искусственного incident. Пересчет создает immutable report revision; incidentSnapshot фиксирует состояние на момент оценки.

UI: создание/start/cancel, progress и причина неполноты; фильтры scenario/route/decision/severity/status; карточка с rule/version, хронологией, effect/invariants и assessment. JSON export обязателен, Markdown дополнительный. Operator-аутентификация, CSRF, escaping, pagination; запросы не становятся HTML. Grafana отражает техническое здоровье, метрики безопасности считает evaluator.

## Физическая модель: 12 таблиц lab

Control: experiments, runs, scenario_jobs, attempts, reports. Audit: log_streams, security_events. Detection: partitions, jobs, processed_events, rule_state, incidents. Runtime-роли и начальная схема: lab-schema.sql → lab-guards.sql → lab-projections.sql → lab-detector-access.sql; затем verify-lab-access.sql. Dedicated LOGIN identities и права shop/evaluator/fixture admin — отдельные deployment migrations. SQL не заменяет Java instrumentation и закрытые JSON-контракты.

В experiments.definition находятся cases/episodes/labels/oracleIds и полные snapshots конфигураций; в runs.fixture_manifest — независимый baseline; runs.command_journal — append-only записи commandId/recipient/kind/epoch/expectedRevision/payloadHash/status/result/recordedAt. Изменение статуса команды добавляет запись, не переписывает старую. Команды выполняются под row lock/CAS run; повтор commandId сверяет payloadHash.

scenario_jobs.plan содержит steps:[{id,ordinal,caseId:null/UUID,episodeId:null/UUID,attackSpecific,requestSpec,concurrentGroup:null/string}], budget и версию. Один scenario_jobs на run хранит неизменяемый план и deadline_at; локальный runner обновляет прогресс через persistence adapter control, без HTTP/heartbeat/lease. Финальный step_manifest/hash относится к выполнению runner, не заменяет STREAM_END producer. Attempts — отдельные строки HTTP-попыток; принадлежность stepId job plan проверяет control. Секреты в plan/evidence не сохраняются.

reports.snapshot — readonly конечное состояние без password_hash; reports.body — Report v1 с полными hashes/snapshots, caseResults:[{caseId,effect,invariantVerdicts,evidenceRefs}], episodeResults:[{episodeId,result,coverage,evidenceRefs}], incidentAssessments:[{incidentId,verdict,matchedEpisodeIds,confirmedImpact,reason,evidenceRefs,incidentSnapshot}], metrics/denominators. COMPLETE требует snapshot, полного logging barrier и detector ACK. В INCONCLUSIVE snapshot может отсутствовать. Case/episode ссылки внутри JSON проверяются приложением, не объявляются SQL FK.

Все JSON-документы имеют schemaVersion=1 и закрытую схему. Лимиты: 100000 событий на run/CLOSE, 4096 evidence IDs и 1024 history entries на incident, 1024 записей command journal. Плановые budgets не выше физических. Experiments/reports имеют INSERT/SELECT без UPDATE/DELETE. Triggers защищают baseline, immutable CLOSE input и прежние history/journal entries.

Hash CLOSE-набора — SHA-256 UTF-8 уникальных lowercase UUID в лексикографическом порядке, по одному с завершающим LF; пустой набор — hash пустых bytes. Его формирует control из уже принятых строк audit после DRAINED, не API. Counts сверяются с expected_last_seq каждого файла. Detector не может менять вход CLOSE job. Отдельная DB lease и таблицы восстановления исполнения не нужны в принятой модели непрерывного процесса.

<!-- BEGIN DATA DICTIONARY -->
## Физический словарь данных

Ниже перечислены все столбцы. `—` означает отсутствие DEFAULT: значение передаёт приложение. UUID создаются приложением. Все FK имеют ON UPDATE NO ACTION; ON DELETE RESTRICT, кроме явно указанных CASCADE. JSONB валидируется по зафиксированной версии контракта до записи; сам JSONB не обеспечивает проверку предметных полей.

### `control.experiments`

Неизменяемая definition с cases/episodes, labels, oracle ids и snapshots конфигураций.

PK: `id`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `id` | `uuid` | нет | — | Experiment UUID |
| `name` | `varchar(120)` | нет | — | Название |
| `definition_version` | `integer` | нет | `1` | Версия структуры |
| `definition` | `jsonb` | нет | — | Полные scenarios, policies, rules, plan и версии компонентов |
| `definition_hash` | `bytea` | нет | — | SHA-256 канонической definition |
| `seed` | `bigint` | нет | — | Seed генераторов |
| `business_time_base` | `timestamptz` | нет | — | База бизнес-часов paired runs |
| `created_at` | `timestamptz` | нет | — | Реальное время |

Общие CHECK: `octet_length(definition_hash) = 32`; `definition_version = 1`.

### `control.runs`

Один target и одна фикстура; связь partition с target приватна.

PK: `id`.
UNIQUE: `partition_id`; `experiment_id, target, repetition`; `id, partition_id`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `id` | `uuid` | нет | — | Run UUID |
| `experiment_id` | `uuid` | нет | — | Эксперимент; FK → `control.experiments.id` |
| `target` | `varchar(16)` | нет | — | VULNERABLE / PROTECTED |
| `repetition` | `integer` | нет | — | Номер повторения |
| `partition_id` | `uuid` | нет | — | Непрозрачный идентификатор detector |
| `state` | `varchar(16)` | нет | `'CREATED'` | Состояние run |
| `revision` | `bigint` | нет | `0` | CAS версия управления |
| `cleanup_safe` | `boolean` | нет | `false` | Разрешение подготовки следующей фикстуры |
| `failure_reason` | `text` | да | — | Причина остановки или неполноты |
| `created_at` | `timestamptz` | нет | — | Реальное время |
| `finished_at` | `timestamptz` | да | — | Время terminal state |
| `fixture_manifest` | `jsonb` | да | — | Независимый baseline; NULL до PREPARING |
| `fixture_hash` | `bytea` | да | — | SHA-256 baseline |
| `command_journal` | `jsonb` | нет | `'[]'::jsonb` | Команды с commandId, epoch, expectedRevision и результатом |

Общие CHECK: `target IN ('VULNERABLE','PROTECTED')`; `state IN ('CREATED','PREPARING','RUNNING','DRAINING','EVALUATING','COMPLETED','FAILED','CANCELLED','INCONCLUSIVE')`; `repetition > 0`; `revision >= 0`; `(fixture_manifest IS NULL) = (fixture_hash IS NULL)`; `fixture_hash IS NULL OR octet_length(fixture_hash) = 32`; `jsonb_typeof(command_journal) = 'array'`; `jsonb_array_length(command_journal) <= 1024`.

### `control.scenario_jobs`

Неизменяемый план встроенного runner; один job на run, без распределенного исполнения.

PK: `id`.
UNIQUE: `run_id`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `id` | `uuid` | нет | — | Job UUID |
| `run_id` | `uuid` | нет | — | Run; FK → `control.runs.id` |
| `epoch` | `uuid` | нет | — | Управляющая эпоха задания |
| `plan` | `jsonb` | нет | — | Исполнимый план без внешних адресов |
| `plan_hash` | `bytea` | нет | — | SHA-256 плана |
| `deadline_at` | `timestamptz` | нет | — | Предельное реальное UTC-время выполнения плана |
| `state` | `varchar(16)` | нет | — | PREPARED / RUNNING / STOPPED / FAILED |
| `step_manifest` | `jsonb` | да | — | Финальный manifest выполнения плана |
| `step_manifest_hash` | `bytea` | да | — | SHA-256 финального manifest |
| `sealed_at` | `timestamptz` | да | — | Реальное время фиксации manifest |

Общие CHECK: `state IN ('PREPARED','RUNNING','STOPPED','FAILED')`; `octet_length(plan_hash) = 32`; `(step_manifest IS NULL) = (step_manifest_hash IS NULL)`; `(step_manifest IS NULL) = (sealed_at IS NULL)`; `step_manifest_hash IS NULL OR octet_length(step_manifest_hash) = 32`.

### `control.attempts`

Один реальный HTTP-attempt, включая retry и timeout.

PK: `request_id`.
UNIQUE: `job_id, step_id, attempt_no`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `request_id` | `uuid` | нет | — | UUID attemptId=requestId, созданный harness до отправки и проверенный gateway |
| `step_id` | `uuid` | нет | — | Step UUID внутри immutable scenario_jobs.plan; логическая ссылка |
| `attempt_no` | `integer` | нет | — | Номер попытки |
| `started_at` | `timestamptz` | нет | — | Реальное время |
| `finished_at` | `timestamptz` | да | — | Ответ/timeout |
| `http_status` | `integer` | да | — | NULL при транспортной ошибке |
| `transport_outcome` | `text` | нет | — | RESPONSE / TIMEOUT / CONNECT_ERROR / CANCELLED |
| `response_evidence` | `jsonb` | нет | — | Allowlist полей; без tokens, secrets, raw body |
| `job_id` | `uuid` | нет | — | Назначение runner; FK → `control.scenario_jobs.id` |

### `control.reports`

Immutable report revision: snapshot и body с caseResults, episodeResults, incidentAssessments и evidence.

PK: `id`.
UNIQUE: `run_id, revision`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `id` | `uuid` | нет | — | Report UUID |
| `run_id` | `uuid` | нет | — | Run; FK → `control.runs.id` |
| `revision` | `integer` | нет | — | Номер пересчета |
| `evaluator_version` | `text` | нет | — | Версия evaluator |
| `completeness` | `text` | нет | — | COMPLETE / INCONCLUSIVE |
| `body` | `jsonb` | нет | — | Полный отчет, coverage, hashes, причины и metrics |
| `body_hash` | `bytea` | нет | — | SHA-256 канонического отчета |
| `created_at` | `timestamptz` | нет | — | Реальное время |
| `snapshot` | `jsonb` | да | — | Конечное readonly бизнес-состояние; NULL при ошибке снимка |
| `snapshot_hash` | `bytea` | да | — | SHA-256 снимка |
| `snapshot_captured_at` | `timestamptz` | да | — | Реальное время снимка после quiescence |

Общие CHECK: `revision > 0`; `completeness IN ('COMPLETE','INCONCLUSIVE')`; `octet_length(body_hash) = 32`; `(snapshot IS NULL) = (snapshot_hash IS NULL)`; `(snapshot IS NULL) = (snapshot_captured_at IS NULL)`; `snapshot_hash IS NULL OR octet_length(snapshot_hash) = 32`; `completeness <> 'COMPLETE' OR snapshot IS NOT NULL`.

### `audit.log_streams`

Один JSONL-файл producer на run; collector отмечает прочтение STREAM_END и всего диапазона seq.

PK: `id`.
UNIQUE: `run_id, producer_kind`; `id, run_id, partition_id`; `file_name`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `id` | `uuid` | нет | — | Stream UUID, выдаёт control |
| `run_id` | `uuid` | нет | — | Run; FK → `control.runs.id` |
| `partition_id` | `uuid` | нет | — | Непрозрачная partition |
| `producer_kind` | `text` | нет | — | GATEWAY / SHOP |
| `source` | `text` | нет | — | Доверенный CloudEvents source |
| `source_instance_id` | `uuid` | нет | — | UUID процесса, неизменяемый во время run |
| `file_name` | `text` | нет | — | Уникальный относительный путь, сформированный control |
| `state` | `text` | нет | `'READING'` | READING / DRAINED / ERROR |
| `last_ingested_seq` | `bigint` | нет | `0` | Последний непрерывно принятый seq |
| `expected_last_seq` | `bigint` | да | — | Значение lastSeq из STREAM_END |
| `end_seen_at` | `timestamptz` | да | — | Реальное время чтения STREAM_END |
| `error_reason` | `text` | да | — | Причина неполноты |
| `registered_at` | `timestamptz` | нет | — | Реальное время регистрации |

Общие CHECK: `producer_kind IN ('GATEWAY','SHOP')`; `state IN ('READING','DRAINED','ERROR')`; `last_ingested_seq BETWEEN 0 AND 100000`; `expected_last_seq IS NULL OR expected_last_seq BETWEEN 0 AND 100000`; `(expected_last_seq IS NULL) = (end_seen_at IS NULL)`; `state <> 'DRAINED' OR (expected_last_seq IS NOT NULL AND last_ingested_seq=expected_last_seq AND error_reason IS NULL)`; `state <> 'ERROR' OR error_reason IS NOT NULL`.

### `audit.security_events`

Валидированное событие из JSONL; append-only; raw payload не доступен detector.

PK: `id`.
UNIQUE: `stream_id, seq`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `id` | `uuid` | нет | — | Глобальный Event UUID |
| `stream_id` | `uuid` | нет | — | Зарегистрированный JSONL stream; FK → `audit.log_streams.id` |
| `seq` | `bigint` | нет | — | Порядковый номер EVENT в файле, начиная с 1 |
| `run_id` | `uuid` | нет | — | Доверенная привязка run; FK → `control.runs.id` |
| `partition_id` | `uuid` | нет | — | Доверенная opaque partition |
| `request_id` | `uuid` | нет | — | HTTP-attempt |
| `trace_id` | `char(32)` | нет | — | Trace context |
| `occurred_at` | `timestamptz` | нет | — | Реальное время наблюдения |
| `received_at` | `timestamptz` | нет | `clock_timestamp()` | Время приема |
| `payload` | `jsonb` | нет | — | Валидированный CloudEvents v1 |
| `payload_hash` | `bytea` | нет | — | SHA-256 canonical payload |

Общие CHECK: `octet_length(payload_hash) = 32`; `jsonb_typeof(payload) = 'object'`; `seq BETWEEN 1 AND 100000`.

Дополнительные индексы: `(partition_id, occurred_at, id)`; `(stream_id)`; `(request_id)`; `(run_id)`.

### `detection.partitions`

Неизменяемый ruleset; один worker на partition внутри единственного процесса detector.

PK: `id`.
UNIQUE: `id, ruleset_hash`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `id` | `uuid` | нет | — | Opaque partition UUID; без FK на control |
| `ruleset_hash` | `bytea` | нет | — | Hash зафиксированного ruleset |
| `ruleset_snapshot` | `jsonb` | нет | — | Полные разрешенные правила и engine version |

Общие CHECK: `octet_length(ruleset_hash) = 32`.

### `detection.jobs`

Sanitized SQL commands; control вставляет, detector обновляет outcome.

PK: `id`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `id` | `uuid` | нет | — | Job UUID |
| `partition_id` | `uuid` | нет | — | Partition; FK → `detection.partitions.id` |
| `ruleset_hash` | `bytea` | нет | — | Зафиксированный ruleset |
| `kind` | `text` | нет | — | PREPARE / CLOSE / CANCEL |
| `state` | `text` | нет | `'PENDING'` | PENDING / RUNNING / ACK / ERROR |
| `manifest_hash` | `bytea` | да | — | Обязателен для CLOSE |
| `expected_count` | `bigint` | да | — | Размер CLOSE набора |
| `processed_count` | `bigint` | да | — | ACK обработанного набора |
| `errors` | `jsonb` | нет | `'[]'::jsonb` | Безопасные коды ошибок |
| `created_at` | `timestamptz` | нет | — | Реальное время |
| `finished_at` | `timestamptz` | да | — | ACK / ERROR время |
| `expected_event_ids` | `uuid[]` | да | — | Безопасный CLOSE event set; без private manifest |

Общие CHECK: `kind IN ('PREPARE','CLOSE','CANCEL')`; `state IN ('PENDING','RUNNING','ACK','ERROR')`; `(kind = 'CLOSE' AND expected_event_ids IS NOT NULL AND manifest_hash IS NOT NULL AND octet_length(manifest_hash) = 32 AND expected_count IS NOT NULL AND expected_count = cardinality(expected_event_ids)) OR (kind <> 'CLOSE' AND expected_event_ids IS NULL AND manifest_hash IS NULL AND expected_count IS NULL)`; `expected_event_ids IS NULL OR cardinality(expected_event_ids) <= 100000`; `kind <> 'CLOSE' OR state <> 'ACK' OR (processed_count IS NOT NULL AND processed_count = expected_count AND errors = '[]'::jsonb AND finished_at IS NOT NULL)`.

Дополнительные индексы: `(partition_id, state)`.

### `detection.processed_events`

Atomic marker вместе с state/evidence/incident.

PK: `partition_id, ruleset_hash, event_id`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `partition_id` | `uuid` | нет | — | Partition; FK → `detection.partitions.id` |
| `ruleset_hash` | `bytea` | нет | — | Ruleset hash |
| `event_id` | `uuid` | нет | — | Event UUID; без FK на raw audit |
| `processed_at` | `timestamptz` | нет | — | Реальное время |

### `detection.rule_state`

Bounded event-time windows и buffers; версия JSON pinned ruleset.

PK: `partition_id, ruleset_hash, rule_id, correlation_key, window_key`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `partition_id` | `uuid` | нет | — | Partition; FK → `detection.partitions.id` |
| `ruleset_hash` | `bytea` | нет | — | Ruleset hash |
| `rule_id` | `text` | нет | — | Rule ID |
| `correlation_key` | `text` | нет | — | Нормализованный group key |
| `window_key` | `text` | нет | — | Стабильная граница окна или causal key |
| `state` | `jsonb` | нет | — | Ограниченное состояние правила |
| `updated_at` | `timestamptz` | нет | — | Реальное время |

### `detection.incidents`

Наблюдаемая угроза; не содержит TP/FP и confirmed impact.

PK: `id`.
UNIQUE: `partition_id, ruleset_hash, rule_id, correlation_key, window_key`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `id` | `uuid` | нет | — | Incident UUID |
| `partition_id` | `uuid` | нет | — | Partition; FK → `detection.partitions.id` |
| `ruleset_hash` | `bytea` | нет | — | Ruleset hash |
| `rule_id` | `text` | нет | — | Rule ID |
| `rule_version` | `integer` | нет | — | Версия правила |
| `detector_version` | `text` | нет | — | Версия engine |
| `correlation_key` | `text` | нет | — | Ключ корреляции |
| `window_key` | `text` | нет | — | Window/causal key; никогда NULL |
| `category` | `text` | нет | — | Класс угрозы |
| `technique` | `text` | нет | — | Техника |
| `tags` | `text[]` | нет | — | Теги |
| `severity` | `text` | нет | — | LOW / MEDIUM / HIGH |
| `confidence_level` | `text` | нет | — | UNKNOWN / LOW / MEDIUM / HIGH |
| `method` | `text` | нет | — | RULE / CORRELATION |
| `analysis_state` | `text` | нет | `'PROVISIONAL'` | PROVISIONAL / FINAL / RETRACTED |
| `status` | `text` | нет | `'OPEN'` | OPEN / ACKNOWLEDGED / RESOLVED |
| `revision` | `bigint` | нет | `0` | Версия CAS обновлений |
| `impact_status` | `text` | нет | `'UNKNOWN'` | SUSPECTED / UNKNOWN |
| `summary` | `jsonb` | нет | — | Whitelist evidence summary и ресурсы |
| `created_at` | `timestamptz` | нет | — | Реальное время создания |
| `detected_at` | `timestamptz` | нет | — | Реальное время обнаружения |
| `updated_at` | `timestamptz` | нет | — | Последнее изменение |
| `evidence_event_ids` | `uuid[]` | нет | `'{}'::uuid[]` | Уникальные UUID наблюдений; проверяются через safe view |
| `history` | `jsonb` | нет | `'[]'::jsonb` | Append-only записи analysis/operator transitions |

Общие CHECK: `analysis_state IN ('PROVISIONAL','FINAL','RETRACTED')`; `status IN ('OPEN','ACKNOWLEDGED','RESOLVED')`; `impact_status IN ('SUSPECTED','UNKNOWN')`; `confidence_level IN ('UNKNOWN','LOW','MEDIUM','HIGH')`; `severity IN ('LOW','MEDIUM','HIGH')`; `method IN ('RULE','CORRELATION')`; `cardinality(evidence_event_ids) <= 4096`; `jsonb_typeof(history) = 'array'`; `jsonb_array_length(history) <= 1024`; `revision >= 0`; `rule_version > 0`.

Дополнительные индексы: `(partition_id, analysis_state, created_at)`.

<!-- END DATA DICTIONARY -->
