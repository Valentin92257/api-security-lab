# Логирование, обнаружение и дашборд

## Как detector получает информацию

Единственный источник наблюдений Detector - access-логи Gateway и Shop. Producers отправляют LogBatch напрямую по HTTP/JSON; Detector хранит логи и анализирует их у себя. Он не обращается к бизнес-БД, сценарию или эталону. SQL-проекции и сборщика логов в Control нет.

Это инструментированный API: интеграция требует выполнения контракта логирования ниже. Детектор не разбирает произвольные текстовые сообщения и не получает от приложения готовый диагноз «атака успешна». Он получает HTTP-наблюдения. Операционные console-логи, метрики и traces дополнительно идут в LGTM, но не являются входом алгоритма обнаружения.

## Граница MVP

Процессы и хранилища считаются непрерывно работающими в течение run. Восстановление после падения не реализуется. Сбой, перезапуск, ошибка записи/парсинга или нарушение протокола делает run INCONCLUSIVE; опыт повторяют с новым runId после перезапуска стенда и подготовки фикстур. При старте control прежние незавершенные runs не продолжаются.

Между бизнес-commit и записью лога нет атомарности. Это принятое ограничение, а не гарантия отсутствия потерь при падении. Нормальная задержка чтения/анализа остается возможной, поэтому завершение требует CLOSED с lastSequence и COMPLETED анализа Detector. Нельзя считать отсутствие инцидента доказанным до завершения потока.

## Требования к логированию gateway и API

1. Одна итоговая access-запись на запрос у каждого producer. Если Gateway остановил запрос до Shop, запись будет только у Gateway. Совпадающий requestId связывает две записи одного запроса.
2. sequence назначается от 1 отдельно для producer/run. Дубликат producer/sequence с тем же содержимым безопасен; иное содержимое даёт 409. Пачка проверяется до сохранения и при конфликте отклоняется целиком.
3. runId задаётся URL доставки и активным Runtime-контекстом; instanceId процесса проверяется Control до/после завершения. RequestId создаётся Runner и передаётся в X-Request-Id без X-Lab-заголовков.
4. Запись формируется после commit/rollback и обработки исключения с реальным HTTP-status, до уменьшения in-flight. Sampling и тихое отбрасывание запрещены. Client timeout не доказывает rollback сервера.
5. Обе реализации API используют один наблюдающий код и одинаковые поля. Политика защиты может различаться, формат наблюдений - нет. Подготовительные Fixture/Runtime операции не попадают в экспериментальный поток.
6. Пароли, JWT, Authorization, cookies и исходное тело не сохраняются. Поля ограничены схемой; path включает исходный query, поэтому сценарные URL не должны содержать credentials.
7. До 100 записей в LogBatch, 64 KiB на запись, 8 MiB на пачку, 100000 записей на producer/run. Превышение не приводит к усечению: опыт недействителен. Ошибка логирования после commit не отменяет созданный заказ.
8. CLOSED подтверждает in-flight=0 и окончательный lastSequence. Поздние отклонённые запросы закрытого run пишутся только в operational log, не меняют завершённый поток. Локальный JSONL допустим, но не является транспортом между компонентами.

## Формат access-лога v1

LogBatch содержит producer GATEWAY/SHOP и entries. В каждой записи обязательны sequence, requestId, time, remote_addr, user, method, path, route, status, bytes, request_time, user_agent и req_hash; неизвестные user/route/user_agent/req_hash равны null. [JSON Schema](../contracts/schemas/logs.yaml) и [пример пачки](../contracts/examples/log-batch.json) задают точные типы и пределы.

time - реальное UTC-время завершения; request_time - длительность в секундах, bytes - размер тела ответа. remote_addr - один IP виртуального клиента из доверенного X-Forwarded-For. Gateway доверяет только генератору, Shop - только Gateway; недоверенный заголовок игнорируется. path содержит исходный путь/query, route - шаблон маршрута либо null. user - подтверждённый userId либо null до аутентификации.

req_hash - lowercase hex SHA-256 от method + LF + исходного path/query + LF + полного тела запроса. Заголовки и requestId не входят. Пустое тело хэшируется, неполное даёт null. Совпадение хэшей - признак повторного содержимого, не доказательство атаки. Бизнес-время заказа не подменяет время логов.

## Наблюдаемые признаки

| Наблюдение | Возможная интерпретация и ограничение |
|---|---|
| Серия login 401 одного IP, затем 200 | Подозрительные входы; без login в логе нельзя уверенно отличить stuffing от brute force |
| Последовательные ID, много 403/404 | Возможный перебор объектов; принадлежность объекта неизвестна |
| SQL-подобный query и изменение status/bytes | Возможная injection; апострофы бывают легитимными, успешность вмешательства не доказана |
| Повтор req_hash пользователя | Возможный Replay либо обычный технический retry |
| Всплеск cart/orders | Подозрительная серия, но нарушение цены/купона/атомарности по логу не доказать |

Owner, stateChanged, cartRevision, idempotency outcome, цена и результат oracle в Detector не передаются. Правила считают уникальные requestId, а не две записи producers. Сценарий, режим и метки ATTACK/BENIGN остаются приватными для Control.

## Доставка и права

Log-ingestor в Control не нужен. Токены Shop/Gateway разрешают запись только своего producer через Detector API; токен Control - управление и чтение. У Detector нет credentials Shop/Control. Runtime-роль не наследует migration/admin. Физическая схема хранения принадлежит реализации Detector.

Control подготавливает анализ, затем после CLOSED Gateway и Shop отправляет gatewayLastSequence, shopLastSequence и deadlineAt в finishAnalysis. Detector ждёт непрерывные диапазоны 1..N. Наличие последнего номера не заменяет проверку всего диапазона; для пустого producer N=0.

ACCEPTING принимает новые записи; FINISHING - только внутри объявленных границ; COMPLETED/FAILED - только уже сохранённые идентичные повторы. Новая запись после завершения, конфликт содержимого или граница finish ниже сохранённого sequence дают 409. Изменённый повтор finish - 409, идентичный возвращает текущее состояние.

COMPLETED публикует observedRequestIds (до 100000 уникальных ID, поскольку все Shop-запросы проходят через Gateway). Control сверяет их с attempts: отсутствие requestId с transportOutcome=RESPONSE означает неполноту. TIMEOUT не доказывает отсутствие операции или записи. Ошибка, restart, пропуск либо deadline дают INCONCLUSIVE, без продолжения старого run.

## Правила и обработка detector

Detector - отдельный процесс, не блокирует бизнес-HTTP. YAML допускает match, countWindow и именованные Java kinds для последовательностей; только whitelist полей AccessLog, eq/in, count/countDistinct, bounded window/groupBy и ограниченное evidence. SpEL, SQL и произвольный код запрещены.

```yaml
id: auth-failure-burst
version: 1
kind: countWindow
where:
  - field: path
    op: eq
    value: /api/v1/auth/login
  - field: status
    op: eq
    value: 401
groupBy: [remote_addr]
window: PT60S
aggregate:
  function: countDistinct
  field: requestId
threshold: 10
family: CREDENTIAL_STUFFING
```

Это иллюстрация внутреннего правила, не дополнительная схема публичного API. Порог калибруется до измерений. prepare валидирует ruleset, лимиты окон/групп/evidence; unknown field/operator/schema запрещают запуск, hot reload нет. Начальные правила исследуют серии auth-failure, failure-then-success, перебор ID, SQL-подобный query и повтор req_hash, не получают готовый результат бизнес-проверки.

Один процесс Detector изолирует состояние по runId; DB lease/fencing и failover не применяются. Повторы не добавляют вклад в правила. Обработка должна учитывать позднюю запись с меньшим sequence, а не только двигать общий watermark по максимуму. Состояние и прогресс хранения/обработки согласуются внутри Detector.

Окна event-time; равные timestamps упорядочиваются детерминированно. Поздние записи пересчитывают затронутые bounded windows. Внутренние предварительные выводы не публикуются. После finish и обработки полных диапазонов анализ становится COMPLETED, итоговые incidents неизменяемы. До завершения их чтение даёт 409. Ошибка или превышение лимита не маскируется усечением.

## Инцидент, оценка и UI

Incident содержит incidentId, detectedAt, requestIds, description и необязательный family. Он сообщает подозрение на попытку атаки, а не подтверждает её успех. Severity, confidence, операторские статусы и история изменений в текущем контракте не требуются.

Control отдельно сопоставляет итоговые incidents с attack-specific steps/episodes и независимым эталоном. IncidentAssessment содержит incident, verdict TRUE_POSITIVE/FALSE_POSITIVE/UNASSESSED, matchedEpisodeIds и reason. Блокированная попытка может быть TP. Ошибка или отсутствие family не меняет вердикт обнаружения; ошибку классификации Control поясняет в reason. Подготовительный login и общий IP сами по себе не подтверждают атакующий episode.

FN хранится в detection результата case/episode без искусственного incident. Поле detection выводится из оценок инцидентов и полноты шагов по [общим правилам](../contracts/semantics.md), одинаково у всех cases episode и независимо от effect. Связанный TP не может сочетаться с MISSED. При незавершённом анализе incidents=null и detection=INCONCLUSIVE.

UI: выбор families/seed, создание и запуск trial одной командой, cancel, общий progress и причина неполноты, cases и итоговые incidents с assessment. JSON-отчёт сохраняется на стороне UI. Operator Bearer, escaping, pagination и отсутствие секретов обязательны; Control использует явный Authorization, без cookie-аутентификации. Grafana отражает техническое здоровье; показатели безопасности UI вычисляет из отчёта.

<!-- BEGIN LEGACY LAB MODEL -->
## Архив: прежняя физическая модель lab

Следующий раздел сохранён для истории проектирования и чтения [архивных SQL/DBML](data/legacy-lab/README.md). Он описывает прежние общие audit-view, SQL jobs и расширенные инциденты. Это не текущий контракт и не миграции действующего стенда: актуальные HTTP-интерфейсы описаны выше и в [contracts](../contracts/README.md). Внутренние таблицы Control/Detector будут определены при реализации.

Control: experiments, runs, scenario_jobs, attempts, reports. Audit: log_streams, security_events. Detection: partitions, jobs, processed_events, rule_state, incidents. Архивные runtime-роли и схема в `data/legacy-lab/`: lab-schema.sql → lab-guards.sql → lab-projections.sql → lab-detector-access.sql; затем verify-lab-access.sql. Dedicated LOGIN identities и права shop/evaluator/fixture admin - отдельные deployment migrations. SQL не заменяет Java instrumentation и закрытые JSON-контракты.

В experiments.definition находятся cases/episodes/labels/oracleIds и полные snapshots конфигураций; в runs.fixture_manifest - независимый baseline; runs.command_journal - append-only записи commandId/recipient/kind/epoch/expectedRevision/payloadHash/status/result/recordedAt. Изменение статуса команды добавляет запись, не переписывает старую. Команды выполняются под row lock/CAS run; повтор commandId сверяет payloadHash.

scenario_jobs.plan содержит steps:[{id,ordinal,caseId:null/UUID,episodeId:null/UUID,attackSpecific,requestSpec,concurrentGroup:null/string}], budget и версию. Один scenario_jobs на run хранит неизменяемый план и deadline_at; локальный runner обновляет прогресс через persistence adapter control, без HTTP/heartbeat/lease. Финальный step_manifest/hash относится к выполнению runner, не заменяет STREAM_END producer. Attempts - отдельные строки HTTP-попыток; принадлежность stepId job plan проверяет control. Секреты в plan/evidence не сохраняются.

reports.snapshot - readonly конечное состояние без password_hash; reports.body - Report v1 с полными hashes/snapshots, caseResults:[{caseId,effect,invariantVerdicts,evidenceRefs}], episodeResults:[{episodeId,result,coverage,evidenceRefs}], incidentAssessments:[{incidentId,verdict,matchedEpisodeIds,confirmedImpact,reason,evidenceRefs,incidentSnapshot}], metrics/denominators. COMPLETE требует snapshot, полного logging barrier и detector ACK. В INCONCLUSIVE snapshot может отсутствовать. Case/episode ссылки внутри JSON проверяются приложением, не объявляются SQL FK.

Все JSON-документы имеют schemaVersion=1 и закрытую схему. Лимиты: 100000 событий на run/CLOSE, 4096 evidence IDs и 1024 history entries на incident, 1024 записей command journal. Плановые budgets не выше физических. Experiments/reports имеют INSERT/SELECT без UPDATE/DELETE. Triggers защищают baseline, immutable CLOSE input и прежние history/journal entries.

Hash CLOSE-набора - SHA-256 UTF-8 уникальных lowercase UUID в лексикографическом порядке, по одному с завершающим LF; пустой набор - hash пустых bytes. Его формирует control из уже принятых строк audit после DRAINED, не API. Counts сверяются с expected_last_seq каждого файла. Detector не может менять вход CLOSE job. Отдельная DB lease и таблицы восстановления исполнения не нужны в принятой модели непрерывного процесса.

<!-- BEGIN DATA DICTIONARY -->
## Архивный словарь данных

Ниже перечислены все столбцы. `-` означает отсутствие DEFAULT: значение передаёт приложение. UUID создаются приложением. Все FK имеют ON UPDATE NO ACTION; ON DELETE RESTRICT, кроме явно указанных CASCADE. JSONB валидируется по зафиксированной версии контракта до записи; сам JSONB не обеспечивает проверку предметных полей.

### `control.experiments`

Неизменяемая definition с cases/episodes, labels, oracle ids и snapshots конфигураций.

PK: `id`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `id` | `uuid` | нет | - | Experiment UUID |
| `name` | `varchar(120)` | нет | - | Название |
| `definition_version` | `integer` | нет | `1` | Версия структуры |
| `definition` | `jsonb` | нет | - | Полные scenarios, policies, rules, plan и версии компонентов |
| `definition_hash` | `bytea` | нет | - | SHA-256 канонической definition |
| `seed` | `bigint` | нет | - | Seed генераторов |
| `business_time_base` | `timestamptz` | нет | - | База бизнес-часов paired runs |
| `created_at` | `timestamptz` | нет | - | Реальное время |

Общие CHECK: `octet_length(definition_hash) = 32`; `definition_version = 1`.

### `control.runs`

Один target и одна фикстура; связь partition с target приватна.

PK: `id`.
UNIQUE: `partition_id`; `experiment_id, target, repetition`; `id, partition_id`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `id` | `uuid` | нет | - | Run UUID |
| `experiment_id` | `uuid` | нет | - | Эксперимент; FK → `control.experiments.id` |
| `target` | `varchar(16)` | нет | - | VULNERABLE / PROTECTED |
| `repetition` | `integer` | нет | - | Номер повторения |
| `partition_id` | `uuid` | нет | - | Непрозрачный идентификатор detector |
| `state` | `varchar(16)` | нет | `'CREATED'` | Состояние run |
| `revision` | `bigint` | нет | `0` | CAS версия управления |
| `cleanup_safe` | `boolean` | нет | `false` | Разрешение подготовки следующей фикстуры |
| `failure_reason` | `text` | да | - | Причина остановки или неполноты |
| `created_at` | `timestamptz` | нет | - | Реальное время |
| `finished_at` | `timestamptz` | да | - | Время terminal state |
| `fixture_manifest` | `jsonb` | да | - | Независимый baseline; NULL до PREPARING |
| `fixture_hash` | `bytea` | да | - | SHA-256 baseline |
| `command_journal` | `jsonb` | нет | `'[]'::jsonb` | Команды с commandId, epoch, expectedRevision и результатом |

Общие CHECK: `target IN ('VULNERABLE','PROTECTED')`; `state IN ('CREATED','PREPARING','RUNNING','DRAINING','EVALUATING','COMPLETED','FAILED','CANCELLED','INCONCLUSIVE')`; `repetition > 0`; `revision >= 0`; `(fixture_manifest IS NULL) = (fixture_hash IS NULL)`; `fixture_hash IS NULL OR octet_length(fixture_hash) = 32`; `jsonb_typeof(command_journal) = 'array'`; `jsonb_array_length(command_journal) <= 1024`.

### `control.scenario_jobs`

Неизменяемый план встроенного runner; один job на run, без распределенного исполнения.

PK: `id`.
UNIQUE: `run_id`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `id` | `uuid` | нет | - | Job UUID |
| `run_id` | `uuid` | нет | - | Run; FK → `control.runs.id` |
| `epoch` | `uuid` | нет | - | Управляющая эпоха задания |
| `plan` | `jsonb` | нет | - | Исполнимый план без внешних адресов |
| `plan_hash` | `bytea` | нет | - | SHA-256 плана |
| `deadline_at` | `timestamptz` | нет | - | Предельное реальное UTC-время выполнения плана |
| `state` | `varchar(16)` | нет | - | PREPARED / RUNNING / STOPPED / FAILED |
| `step_manifest` | `jsonb` | да | - | Финальный manifest выполнения плана |
| `step_manifest_hash` | `bytea` | да | - | SHA-256 финального manifest |
| `sealed_at` | `timestamptz` | да | - | Реальное время фиксации manifest |

Общие CHECK: `state IN ('PREPARED','RUNNING','STOPPED','FAILED')`; `octet_length(plan_hash) = 32`; `(step_manifest IS NULL) = (step_manifest_hash IS NULL)`; `(step_manifest IS NULL) = (sealed_at IS NULL)`; `step_manifest_hash IS NULL OR octet_length(step_manifest_hash) = 32`.

### `control.attempts`

Один реальный HTTP-attempt, включая retry и timeout.

PK: `request_id`.
UNIQUE: `job_id, step_id, attempt_no`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `request_id` | `uuid` | нет | - | UUID attemptId=requestId, созданный harness до отправки и проверенный gateway |
| `step_id` | `uuid` | нет | - | Step UUID внутри immutable scenario_jobs.plan; логическая ссылка |
| `attempt_no` | `integer` | нет | - | Номер попытки |
| `started_at` | `timestamptz` | нет | - | Реальное время |
| `finished_at` | `timestamptz` | да | - | Ответ/timeout |
| `http_status` | `integer` | да | - | NULL при транспортной ошибке |
| `transport_outcome` | `text` | нет | - | RESPONSE / TIMEOUT / CONNECT_ERROR / CANCELLED |
| `response_evidence` | `jsonb` | нет | - | Allowlist полей; без tokens, secrets, raw body |
| `job_id` | `uuid` | нет | - | Назначение runner; FK → `control.scenario_jobs.id` |

### `control.reports`

Immutable report revision: snapshot и body с caseResults, episodeResults, incidentAssessments и evidence.

PK: `id`.
UNIQUE: `run_id, revision`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `id` | `uuid` | нет | - | Report UUID |
| `run_id` | `uuid` | нет | - | Run; FK → `control.runs.id` |
| `revision` | `integer` | нет | - | Номер пересчета |
| `evaluator_version` | `text` | нет | - | Версия evaluator |
| `completeness` | `text` | нет | - | COMPLETE / INCONCLUSIVE |
| `body` | `jsonb` | нет | - | Полный отчет, coverage, hashes, причины и metrics |
| `body_hash` | `bytea` | нет | - | SHA-256 канонического отчета |
| `created_at` | `timestamptz` | нет | - | Реальное время |
| `snapshot` | `jsonb` | да | - | Конечное readonly бизнес-состояние; NULL при ошибке снимка |
| `snapshot_hash` | `bytea` | да | - | SHA-256 снимка |
| `snapshot_captured_at` | `timestamptz` | да | - | Реальное время снимка после quiescence |

Общие CHECK: `revision > 0`; `completeness IN ('COMPLETE','INCONCLUSIVE')`; `octet_length(body_hash) = 32`; `(snapshot IS NULL) = (snapshot_hash IS NULL)`; `(snapshot IS NULL) = (snapshot_captured_at IS NULL)`; `snapshot_hash IS NULL OR octet_length(snapshot_hash) = 32`; `completeness <> 'COMPLETE' OR snapshot IS NOT NULL`.

### `audit.log_streams`

Один JSONL-файл producer на run; collector отмечает прочтение STREAM_END и всего диапазона seq.

PK: `id`.
UNIQUE: `run_id, producer_kind`; `id, run_id, partition_id`; `file_name`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `id` | `uuid` | нет | - | Stream UUID, выдаёт control |
| `run_id` | `uuid` | нет | - | Run; FK → `control.runs.id` |
| `partition_id` | `uuid` | нет | - | Непрозрачная partition |
| `producer_kind` | `text` | нет | - | GATEWAY / SHOP |
| `source` | `text` | нет | - | Доверенный CloudEvents source |
| `source_instance_id` | `uuid` | нет | - | UUID процесса, неизменяемый во время run |
| `file_name` | `text` | нет | - | Уникальный относительный путь, сформированный control |
| `state` | `text` | нет | `'READING'` | READING / DRAINED / ERROR |
| `last_ingested_seq` | `bigint` | нет | `0` | Последний непрерывно принятый seq |
| `expected_last_seq` | `bigint` | да | - | Значение lastSeq из STREAM_END |
| `end_seen_at` | `timestamptz` | да | - | Реальное время чтения STREAM_END |
| `error_reason` | `text` | да | - | Причина неполноты |
| `registered_at` | `timestamptz` | нет | - | Реальное время регистрации |

Общие CHECK: `producer_kind IN ('GATEWAY','SHOP')`; `state IN ('READING','DRAINED','ERROR')`; `last_ingested_seq BETWEEN 0 AND 100000`; `expected_last_seq IS NULL OR expected_last_seq BETWEEN 0 AND 100000`; `(expected_last_seq IS NULL) = (end_seen_at IS NULL)`; `state <> 'DRAINED' OR (expected_last_seq IS NOT NULL AND last_ingested_seq=expected_last_seq AND error_reason IS NULL)`; `state <> 'ERROR' OR error_reason IS NOT NULL`.

### `audit.security_events`

Валидированное событие из JSONL; append-only; raw payload не доступен detector.

PK: `id`.
UNIQUE: `stream_id, seq`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `id` | `uuid` | нет | - | Глобальный Event UUID |
| `stream_id` | `uuid` | нет | - | Зарегистрированный JSONL stream; FK → `audit.log_streams.id` |
| `seq` | `bigint` | нет | - | Порядковый номер EVENT в файле, начиная с 1 |
| `run_id` | `uuid` | нет | - | Доверенная привязка run; FK → `control.runs.id` |
| `partition_id` | `uuid` | нет | - | Доверенная opaque partition |
| `request_id` | `uuid` | нет | - | HTTP-attempt |
| `trace_id` | `char(32)` | нет | - | Trace context |
| `occurred_at` | `timestamptz` | нет | - | Реальное время наблюдения |
| `received_at` | `timestamptz` | нет | `clock_timestamp()` | Время приема |
| `payload` | `jsonb` | нет | - | Валидированный CloudEvents v1 |
| `payload_hash` | `bytea` | нет | - | SHA-256 canonical payload |

Общие CHECK: `octet_length(payload_hash) = 32`; `jsonb_typeof(payload) = 'object'`; `seq BETWEEN 1 AND 100000`.

Дополнительные индексы: `(partition_id, occurred_at, id)`; `(stream_id)`; `(request_id)`; `(run_id)`.

### `detection.partitions`

Неизменяемый ruleset; один worker на partition внутри единственного процесса detector.

PK: `id`.
UNIQUE: `id, ruleset_hash`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `id` | `uuid` | нет | - | Opaque partition UUID; без FK на control |
| `ruleset_hash` | `bytea` | нет | - | Hash зафиксированного ruleset |
| `ruleset_snapshot` | `jsonb` | нет | - | Полные разрешенные правила и engine version |

Общие CHECK: `octet_length(ruleset_hash) = 32`.

### `detection.jobs`

Sanitized SQL commands; control вставляет, detector обновляет outcome.

PK: `id`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `id` | `uuid` | нет | - | Job UUID |
| `partition_id` | `uuid` | нет | - | Partition; FK → `detection.partitions.id` |
| `ruleset_hash` | `bytea` | нет | - | Зафиксированный ruleset |
| `kind` | `text` | нет | - | PREPARE / CLOSE / CANCEL |
| `state` | `text` | нет | `'PENDING'` | PENDING / RUNNING / ACK / ERROR |
| `manifest_hash` | `bytea` | да | - | Обязателен для CLOSE |
| `expected_count` | `bigint` | да | - | Размер CLOSE набора |
| `processed_count` | `bigint` | да | - | ACK обработанного набора |
| `errors` | `jsonb` | нет | `'[]'::jsonb` | Безопасные коды ошибок |
| `created_at` | `timestamptz` | нет | - | Реальное время |
| `finished_at` | `timestamptz` | да | - | ACK / ERROR время |
| `expected_event_ids` | `uuid[]` | да | - | Безопасный CLOSE event set; без private manifest |

Общие CHECK: `kind IN ('PREPARE','CLOSE','CANCEL')`; `state IN ('PENDING','RUNNING','ACK','ERROR')`; `(kind = 'CLOSE' AND expected_event_ids IS NOT NULL AND manifest_hash IS NOT NULL AND octet_length(manifest_hash) = 32 AND expected_count IS NOT NULL AND expected_count = cardinality(expected_event_ids)) OR (kind <> 'CLOSE' AND expected_event_ids IS NULL AND manifest_hash IS NULL AND expected_count IS NULL)`; `expected_event_ids IS NULL OR cardinality(expected_event_ids) <= 100000`; `kind <> 'CLOSE' OR state <> 'ACK' OR (processed_count IS NOT NULL AND processed_count = expected_count AND errors = '[]'::jsonb AND finished_at IS NOT NULL)`.

Дополнительные индексы: `(partition_id, state)`.

### `detection.processed_events`

Atomic marker вместе с state/evidence/incident.

PK: `partition_id, ruleset_hash, event_id`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `partition_id` | `uuid` | нет | - | Partition; FK → `detection.partitions.id` |
| `ruleset_hash` | `bytea` | нет | - | Ruleset hash |
| `event_id` | `uuid` | нет | - | Event UUID; без FK на raw audit |
| `processed_at` | `timestamptz` | нет | - | Реальное время |

### `detection.rule_state`

Bounded event-time windows и buffers; версия JSON pinned ruleset.

PK: `partition_id, ruleset_hash, rule_id, correlation_key, window_key`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `partition_id` | `uuid` | нет | - | Partition; FK → `detection.partitions.id` |
| `ruleset_hash` | `bytea` | нет | - | Ruleset hash |
| `rule_id` | `text` | нет | - | Rule ID |
| `correlation_key` | `text` | нет | - | Нормализованный group key |
| `window_key` | `text` | нет | - | Стабильная граница окна или causal key |
| `state` | `jsonb` | нет | - | Ограниченное состояние правила |
| `updated_at` | `timestamptz` | нет | - | Реальное время |

### `detection.incidents`

Наблюдаемая угроза; не содержит TP/FP и confirmed impact.

PK: `id`.
UNIQUE: `partition_id, ruleset_hash, rule_id, correlation_key, window_key`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `id` | `uuid` | нет | - | Incident UUID |
| `partition_id` | `uuid` | нет | - | Partition; FK → `detection.partitions.id` |
| `ruleset_hash` | `bytea` | нет | - | Ruleset hash |
| `rule_id` | `text` | нет | - | Rule ID |
| `rule_version` | `integer` | нет | - | Версия правила |
| `detector_version` | `text` | нет | - | Версия engine |
| `correlation_key` | `text` | нет | - | Ключ корреляции |
| `window_key` | `text` | нет | - | Window/causal key; никогда NULL |
| `category` | `text` | нет | - | Класс угрозы |
| `technique` | `text` | нет | - | Техника |
| `tags` | `text[]` | нет | - | Теги |
| `severity` | `text` | нет | - | LOW / MEDIUM / HIGH |
| `confidence_level` | `text` | нет | - | UNKNOWN / LOW / MEDIUM / HIGH |
| `method` | `text` | нет | - | RULE / CORRELATION |
| `analysis_state` | `text` | нет | `'PROVISIONAL'` | PROVISIONAL / FINAL / RETRACTED |
| `status` | `text` | нет | `'OPEN'` | OPEN / ACKNOWLEDGED / RESOLVED |
| `revision` | `bigint` | нет | `0` | Версия CAS обновлений |
| `impact_status` | `text` | нет | `'UNKNOWN'` | SUSPECTED / UNKNOWN |
| `summary` | `jsonb` | нет | - | Whitelist evidence summary и ресурсы |
| `created_at` | `timestamptz` | нет | - | Реальное время создания |
| `detected_at` | `timestamptz` | нет | - | Реальное время обнаружения |
| `updated_at` | `timestamptz` | нет | - | Последнее изменение |
| `evidence_event_ids` | `uuid[]` | нет | `'{}'::uuid[]` | Уникальные UUID наблюдений; проверяются через safe view |
| `history` | `jsonb` | нет | `'[]'::jsonb` | Append-only записи analysis/operator transitions |

Общие CHECK: `analysis_state IN ('PROVISIONAL','FINAL','RETRACTED')`; `status IN ('OPEN','ACKNOWLEDGED','RESOLVED')`; `impact_status IN ('SUSPECTED','UNKNOWN')`; `confidence_level IN ('UNKNOWN','LOW','MEDIUM','HIGH')`; `severity IN ('LOW','MEDIUM','HIGH')`; `method IN ('RULE','CORRELATION')`; `cardinality(evidence_event_ids) <= 4096`; `jsonb_typeof(history) = 'array'`; `jsonb_array_length(history) <= 1024`; `revision >= 0`; `rule_version > 0`.

Дополнительные индексы: `(partition_id, analysis_state, created_at)`.

<!-- END DATA DICTIONARY -->

Для CHECKOUT обязательны cartKey, cartRevision и idempotencyKeyHash. cartRevision обозначает запрошенную исходную версию, а не revision после очистки. Новый эффект содержит orderId, stateChanged=true и idempotencyOutcome=NEW; возврат сохранённого ответа - тот же orderId, stateChanged=false и REPLAYED. Отказ повторной cartRevision - stateChanged=false и CONFLICT. Detector считает разные orderId одной пары cartKey/cartRevision; повторный лог или cached retry не являются новым эффектом.

<!-- END LEGACY LAB MODEL -->
