# События, обнаружение и дашборд

## Контракт события

SecurityEvent — CloudEvents structured JSON версии 1.0, media type `application/cloudevents+json`. Envelope содержит specversion, id, source, type, time, datacontenttype и dataschema; subject optional. Предметные объекты находятся в data. Событие описывает наблюдаемый факт, а не ground truth, attackSucceeded или truePositive.

```json
{
  "specversion": "1.0",
  "id": "a327de28-e16c-49c7-bcb2-42b33a7ca739",
  "source": "urn:lab:shop:vulnerable",
  "type": "lab.object.accessed.v1",
  "subject": "order/123",
  "time": "2026-10-21T10:00:00.123Z",
  "datacontenttype": "application/json",
  "dataschema": "urn:lab:schema:security-event:1",
  "data": {
    "schemaVersion": 1,
    "context": {
      "runId": "42c28083-c1c9-407b-b1dd-7f58fab42e90",
      "partitionId": "98ceecbb-5f06-40cd-bd39-205fc687381a",
      "producerEpoch": "f599b2a5-b117-4a4a-82c2-df77d3452da9",
      "requestId": "e42fb0db-45dc-4b1a-8157-66c3a815e450",
      "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
    },
    "actor": {"kind": "USER", "id": "7", "authenticated": true},
    "request": {"method": "GET", "route": "/api/v1/orders/{orderId}", "sourceKey": "src-01", "sizeBytes": 0},
    "action": {"kind": "READ", "resourceType": "ORDER", "resourceId": "123"},
    "outcome": {"status": "SUCCESS", "httpStatus": 200, "reasonCode": "OK", "durationMs": 12},
    "observations": {"subjectMatchesOwner": false, "operationExecuted": true, "stateChanged": false}
  }
}
```

EventId — UUID, создаётся один раз; retry доставки сохраняет id и payload. В стенде id глобально уникален. Source устанавливается producer role; conflict id с другим payload — ошибка целостности. Time — реальный UTC момент наблюдения; receivedAt/cursor добавляет storage, не изменяя payload. SchemaVersion — целое; несовместимый формат требует новой версии.

Context обязателен для experiment events: run/partition/epoch/request UUID и 32-hex traceId. Actor.kind USER/ANONYMOUS, id — строковый subject или null, authenticated — boolean. Request содержит method, route template либо UNMATCHED, доверенный sourceKey и sizeBytes. Action при наличии имеет kind, resourceType и resourceId/null. Outcome для terminal events: status SUCCESS/REJECTED/ERROR, httpStatus integer/null, reasonCode enum, durationMs ≥0. Started event не имеет terminal outcome. Неизвестный факт передаётся как null, а не false.

| Type | Producer | Специфические данные |
|---|---|---|
| `lab.http.request.started.v1` | Gateway | Durable receipt принятого request |
| `lab.http.request.completed.v1` | Gateway | Фактический HTTP/transport outcome и terminal receipt |
| `lab.policy.evaluated.v1` | Gateway | Policy snapshotHash, evaluations и enforcedAction |
| `lab.auth.attempted.v1` | Shop | observations.authOutcome SUCCESS/FAILURE; accountKey HMAC; failure actor anonymous |
| `lab.object.accessed.v1` | Shop | Resource и subjectMatchesOwner boolean/null |
| `lab.operation.completed.v1` | Shop | Operation kind, stateChanged, проверяемые локальные факты и correlation keys |
| `lab.input.observed.v1` | Shop | validationCategories из разрешённого enum, без raw payload |

Policy evaluations: policyId/version/mode, evaluationStatus, wouldDecision, reasonCode, durationMs. EnforcedAction — CONTINUE/REJECT/INFRA_REJECT; OFF/OBSERVE не подменяются фактическим BLOCK. Допустимые observations: operationExecuted/stateChanged, quantityWithinBounds, quoteMatchesServer, couponEligibility VALID/EXPIRED/EXHAUSTED/NONE/UNKNOWN, idempotencyOutcome NEW/REPLAYED/CONFLICT/NONE. Checkout correlation: intentHash, idempotencyKeyHash, cartKey/revision, orderId. Input categories: SQL_LIKE, TYPE_MISMATCH, UNKNOWN_FIELD, QUANTITY_OUT_OF_RANGE. Произвольные дополнительные maps не принимаются.

Факты собираются одинаковой наблюдающей логикой обоих режимов. SQL_LIKE — признак входа, не доказательство успешной injection. Локальный calculator может ошибиться; его observations не заменяют независимый evaluator.

## Доступ и доставка

| Представление | Содержание и читатель |
|---|---|
| Raw audit | Control: факты с доверенной run/producer-привязкой, без секретов |
| `audit.detector_observations` | Detector: event id/type/time, opaque partition, producerKind GATEWAY/SHOP, route, actor/source keys, разрешённые facts/outcomes |
| Report projection | Control: join audit, steps, scenario labels, target mode, oracle и incidents |

Detector view строится из whitelist полей, без raw JSON. В ней отсутствуют scenario/case/step ids, expected outcome, fixture labels, target mode/backend name, source URI, policy mode/snapshot hash и oracle results. RequestId/traceId разрешены для join/evidence/dedupe, partition — для изоляции состояния, но не как классификационные признаки YAML. SQL-role не имеет доступа к исходным audit/control/shop таблицам. Для CLOSE получает только безопасный список event IDs в `detection.jobs.expected_event_ids` и его hash.

Shop сохраняет событие успешной mutation в outbox той же транзакции. Publisher вставляет его в lab DB, затем отмечает delivered; crash между действиями вызывает повтор, который устраняет unique eventId. Read-only access/refusal также долговечно фиксируются до ответа; после rollback failure audit пишется отдельной транзакцией.

Gateway сохраняет receipt и решения до downstream. Shop execution ledger фиксирует принятую работу и terminal outcome; успешный terminal record обновляется вместе с бизнес-commit/outbox. При crash незавершённая запись требует reconciliation. Счётчик in-flight в памяти не доказывает отсутствие работ после restart.

После закрытия admission producer запечатывает epoch: event IDs/count/hash, accepted/completed receipts и отсутствие незавершённого исполнения. Collector сверяет не только набор событий, но и lifecycle slots: у каждого принятого request должен быть terminal outcome, у вызванного shop — результат или доказанный abort. Detector обрабатывает набор и закрывает окна. Только затем отчёт считается полным. Поздние отказы со старым epoch фиксируются вне запечатанного набора.

Пароли, bearer/cookie, исходные keys, полный query/body и произвольный user-agent не сохраняются. Для корреляции используются hashes/HMAC; response evidence извлекается по allowlist синтетических полей. Ограничения размера события/evidence и backlog не допускают тихой потери: ошибка полноты видна. Cleanup разрешён после закрытия/export; необработанные события не удаляются. Telemetry retention не управляет доказательным audit.

## Правила detector

Detector работает отдельным процессом и не влияет на HTTP-допуск. Он обнаруживает также незаблокированные действия: например, owner mismatch при 200. В MVP используются match одного события, countWindow и именованные Java kinds для сложной последовательности. YAML допускает только whitelist полей, eq/in, count/countDistinct, bounded window/groupBy и фиксированный шаблон evidence; SpEL, SQL и произвольный код запрещены.

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

Порог примера калибруется до измерений. Ruleset включает engine version, ordered rules, versions/hash и пределы окна/групп/evidence. PREPARE проверяет весь snapshot; unknown field/operator/schema не допускает RUNNING. Hot reload внутри run отсутствует.

Начальный catalog: owner-mismatch; auth-failure-burst по source/account; login-failure-then-success; sql-like-input; invalid-business-input; duplicate-checkout-effect; checkout-replay-conflict. Duplicate effect считает разные orderId по intent/cart revision только у CHECKOUT со stateChanged=true. Cached retry не увеличивает счётчик. Для запросов, остановленных gateway до shop, есть правила на релевантные policy reasons; они дополняют обнаружение фактов незаблокированной атаки.

## Обработка и восстановление

Выборка ищет события без processed marker `(partition, rulesetHash, eventId)`. Один watermark `id > lastSeen` непригоден: меньший sequence id может закоммититься позднее. State, incident/evidence и marker сохраняются одной транзакцией. Известное событие без подходящего правила тоже отмечается обработанным.

На partition работает один worker с DB lease. Окна считаются по event time, равные timestamps упорядочиваются eventId. Sequence kinds используют bounded buffer и пересчёт затронутого окна при позднем событии; лимиты памяти и live lateness входят в snapshot. Переполнение делает анализ неполным.

Live incidents имеют analysisState PROVISIONAL. CLOSE детерминированно обрабатывает полный подтверждённый manifest, закрывает окна и возвращает ACK с ruleset/manifest hashes, processed count и errors. Итоговый incident имеет FINAL; переставший подтверждаться предварительный — RETRACTED с сохранённой историей. Метрики используют только FINAL. Restart восстанавливает state/markers/jobs, без дублирования логического incident.

## Инцидент и независимая оценка

| Группа | Поля контракта Incident v1 |
|---|---|
| Identity | schemaVersion=1; incidentId/partitionId UUID; createdAt/updatedAt UTC |
| Classification | category AUTHENTICATION_ABUSE/ACCESS_CONTROL/REPLAY/INJECTION/BUSINESS_ABUSE; technique; tags:string[] |
| Assessment | severity LOW/MEDIUM/HIGH; confidenceLevel UNKNOWN/LOW/MEDIUM/HIGH; confidenceScore=null в MVP |
| Lifecycle | status OPEN/ACKNOWLEDGED/RESOLVED; revision; resolution/comment/operator/time nullable до действия |
| Detection | detectorVersion, rulesetHash, ruleId/version, method RULE/CORRELATION, detectedAt, analysisState PROVISIONAL/FINAL/RETRACTED |
| Evidence | eventIds/requestIds; route; actor/source keys; безопасное summary |
| Impact | status SUSPECTED/UNKNOWN; resource types/ids; наблюдаемые изменения |

Уникальный ключ incident: partition/ruleset/rule/correlationKey/window; для match — стабильный causal key вместо window. Evidence может дополняться, но повторная доставка не создаёт новый incident. Severity не является вероятностью, числовая confidence без калибровки не публикуется.

Control отдельно сохраняет IncidentAssessment: incidentId, verdict TP/FP/UNASSESSED, matchedEpisodeIds, evaluatorVersion, reason, confirmedImpact/UNKNOWN и evidence references. Detector не подтверждает ущерб самостоятельно. FN хранится в результате episode без incident. Операторский lifecycle и заметки не меняют автоматическую оценку; пересчёт создаёт новую report revision.

## Дашборд и экспорт

Control UI предоставляет создание/start/cancel эксперимента, статусы с причиной неполноты, сравнение targets и список incidents с фильтрами scenario/route/decision/severity/status. Карточка показывает правило/version, хронологию событий/шагов, реальные decisions, effect/oracle и assessment. Пропуск показывается отдельной карточкой episode, не фиктивным incident.

Обязателен JSON-export experiment/incident report с schemaVersion, hashes, evidence, метриками и знаменателями; Markdown — дополнительный формат. UI использует локальную operator-аутентификацию, CSRF, escaping и пагинацию. Значения из запросов не становятся HTML. Отправка внешних уведомлений и PDF pipeline не входят в MVP. Grafana показывает техническое здоровье; итоговые метрики рассчитывает evaluator.

## Физическое хранение: 13 таблиц для MVP

В базе `lab` остаются 5 таблиц control, 3 audit и 5 detection. Самостоятельные строки нужны для потоковых событий/попыток, транзакционного состояния и объектов, обновляемых независимо. Ограниченные документы одной операции хранятся в JSONB/массивах вместе с владельцем.

| Схема | Таблицы | Что хранится вложенно |
|---|---|---|
| control | experiments, runs, runner_jobs, attempts, reports | Cases/episodes в definition, baseline и журнал команд в run, steps и финальный manifest в runner job, snapshot/результаты/оценки в report |
| audit | producer_epochs, security_events, receipts | Запечатанный список UUID/hash/coverage в producer epoch |
| detection | partitions, jobs, processed_events, rule_state, incidents | CLOSE UUID в job, evidence и история в incident |

Исполняемый порядок для пустой lab DB: `data/lab-schema.sql` → `data/lab-guards.sql` → `data/lab-projections.sql` → `data/lab-detector-access.sql`. Последний файл выполняет bootstrap administrator с CREATE ROLE. `verify-lab-access.sql` проверяет доступ detector к view и запрет raw audit/control без наполнения данных. Dedicated LOGIN users, producer ingest functions и права shop/evaluator/fixture admin задаются отдельными deployment migrations. Эти SQL описывают хранение, а не готовый runtime всех приложений.

### Форматы вложенных документов

Каждый JSON-документ имеет schemaVersion=1 и валидируется закрытым контрактом перед записью. SQL JSONB сам по себе не проверяет набор полей. Все UUID внутри JSON — строки; ссылки между вложенными объектами проверяются приложением до RUNNING/публикации отчета.

| Владелец | Полный состав предметной части |
|---|---|
| experiments.definition | Сохраненные scenarios/policies/rules/версии компонентов; episodes:[{id,scenarioRef,label:BENIGN/MALICIOUS,threatClass,specification}]; cases:[{id,episodeId,goal,oracleIds}] |
| runs.fixture_manifest | Actors/owners/canary, catalogVersions, couponVersions, couponCounters и baseline orderIds; полная структура oracle определена в документе 02 |
| runs.command_journal | Массив {commandId,recipient,kind,epoch,expectedRevision,payloadHash,status:ACCEPTED/COMPLETED/FAILED,result,recordedAt}; изменение статуса добавляет запись, не меняет старую |
| runner_jobs.plan | Версионированный исполнимый план; steps:[{id,ordinal,caseId:null/UUID,episodeId:null/UUID,attackSpecific,requestSpec,concurrentGroup:null/string}], budget; секреты в план не записываются |
| runner_jobs.step_manifest | Версия, ожидаемые/исполненные step IDs, request IDs и итоги остановки; hash рядом отдельным полем |
| producer_epochs.coverage | Версия, acceptedCount, terminalCount, slots:[{requestId,terminalEventId,shopInvoked,shopOutcome,evidenceRefs}], результаты reconciliation |
| reports.snapshot | Версионированный readonly снимок бизнес-состояния без password_hash и секретов; NULL при невозможности снять его |
| reports.body | Полный Report v1: snapshots/config hashes, completeness, метрики/знаменатели; caseResults:[{caseId,effect,invariantVerdicts,evidenceRefs}], episodeResults:[{episodeId,result,coverage,evidenceRefs}], incidentAssessments:[{incidentId,verdict,matchedEpisodeIds,confirmedImpact,reason,evidenceRefs,incidentSnapshot}] |
| incidents.history | Массив {revision,analysisState,status,actor,comment:null/string,resolution:null/string,changedAt,summary}; одна запись при создании, далее одна на revision |

Attempt хранится отдельно: один request_id соответствует одной HTTP-попытке, `(job_id,step_id,attempt_no)` уникален. Step ID проверяется по immutable plan этого job; case/episode принадлежность проверяется по experiment данного run. Подготовительные steps могут не иметь case/episode. Runner по-прежнему не имеет SQL-доступа: control валидирует и сохраняет его results batches.

Команды дедуплицируются по commandId под row lock run. Повтор того же id с другим payloadHash — конфликт; принятая команда выполняется/восстанавливается по журналу, а не повторно запускается вслепую. Переходы ACCEPTED→COMPLETED/FAILED добавляют запись. Блокировка/CAS revision не допускает потерю конкурентного обновления JSONB.

### Ограничения размера и неизменяемость

В MVP лимиты: до 100000 уникальных событий на run/CLOSE-набор, до 4096 evidence UUID и 1024 history entries на incident, до 1024 записей command journal на run. Плановые ограничения rules/budgets не могут быть выше этих физических пределов. Превышение останавливает работу с явной ошибкой полноты и INCONCLUSIVE; данные не обрезаются молча. Растущие потоки audit/attempts/processed markers остаются строками, а не бесконечными JSON-массивами.

Triggers в lab-guards.sql запрещают менять подготовленный baseline, sealed manifest, вход CLOSE job и прошлые записи history/journal. History изменяется только добавлением; обновление incident требует revision+1 и согласованной последней записи. Manifest и evidence UUID уникальны и упорядочены. Cases/results имеют уникальные IDs внутри JSON; это валидируется Java-кодом, не объявляется SQL FK.

Experiments и reports имеют runtime INSERT/SELECT без UPDATE/DELETE. Отчет публикуется одной вставкой, сразу со snapshot и всеми оценками. Пересчет создает новую revision. Изменившийся впоследствии operator status не меняет incidentSnapshot старого отчета.

### Доступ и безопасная проекция

Detector получает SELECT только на `audit.detector_observations`, чтение своих таблиц и ограниченные права изменения lease/state/markers/incidents. Он не читает raw JSON, source URI, producer epoch, runId, target, scenario/step/case/episode, labels, policy mode/hash или oracle. Runtime-role не владеет view/таблицами, не наследует migration/admin role и не имеет BYPASSRLS/superuser.

View построена индивидуальным whitelist, с правами её владельца и security_barrier=true. Она не использует security_invoker=true: detector не получает доступ к базовым таблицам. Nullable boolean остается NULL; producer нормализован до GATEWAY/SHOP. ID/request/trace используются для evidence/join, partition для изоляции, не как классификационные литералы правил. confidenceScore не хранится: REST возвращает null в MVP.

### Аудит, admission и SEAL

Producer epoch регистрирует source/kind/run/partition до admission. Composite FK фиксирует доверенную run/partition привязку. Ingest дополнительно проверяет credential producer, совпадение envelope/context, schema/version/типы и отсутствие секретов. Hash рассчитывает доверенная сторона. Повтор eventId подтверждается только при идентичном payload/hash/provenance; ON CONFLICT DO NOTHING без сравнения недостаточно.

Gateway до downstream атомарно сохраняет started event и receipt. shop_invoked=true ставится до потенциального вызова; crash между отметкой и вызовом требует reconciliation. Terminal event и terminal receipt также атомарны. Shop ledger остается в своей БД; его ссылки на audit логические и проверяются при DRAINING.

Состояния producer epoch: OPEN → CLOSED → SEALED. CLOSE запрещает прием новой работы, SEAL после quiescence сохраняет expected_event_ids/event_set_hash/coverage/sealed_at одним UPDATE. После SEALED строка неизменяема. Ожидаемые события могут еще находиться в outbox, поэтому UUID в массиве не имеют FK на audit. Ingest после SEAL допускает только ожидаемые события запечатанного набора и их идентичные повторы; новые поздние отказы фиксируются вне experiment set.

Hash набора — SHA-256 UTF-8 списка уникальных lowercase UUID в лексикографическом порядке, по одному UUID с завершающим LF; пустой набор — hash пустых bytes. SQL проверяет канонический массив/hash. Count определяется cardinality массива; в CLOSE expected_count обязан совпадать с ним. Для CLOSE control объединяет наборы обоих producer и проверяет общий лимит run.

`audit.delivery_gaps` использует unnest(expected_event_ids) и выявляет отсутствующие/лишние события и незавершенные receipts. Нулевой результат не доказывает quiescence: control также проверяет slots, shop ledger, runtime и DB-сессии. Hash доставленного набора не заменяет expected slots. Неполное покрытие дает INCONCLUSIVE; reset запрещен до cleanupSafe.

### Detector state и CLOSE

Одна partition имеет один неизменяемый ruleset snapshot/hash. Worker перед изменением state блокирует partition и проверяет lease owner/fence/expiry, удерживая lock до commit ограниченного batch. Новое владение увеличивает fence. Старый worker после истечения lease не может коммитить новые результаты.

Выборка выполняется anti-join к processed_events(partition_id,ruleset_hash,event_id), не watermark по sequence. State, incident с evidence/history и processed marker фиксируются одной транзакцией; событие без срабатывания тоже получает marker. Composite FK связывает jobs/markers/state/incidents с одним ruleset partition.

CLOSE job атомарно содержит expected_event_ids/hash/count; detector не вправе менять эти поля. ACK требует наличия и обработки каждого UUID, совпадения hash/count и финализации всех окон. Число processed само по себе не подтверждает совпадение наборов. Поздние события пересчитывают затронутые окна; потеря bounded state или превышение лимита — ошибка полноты.

Evidence хранится в incidents.evidence_event_ids. Приложение проверяет наличие каждого события и совпадение partition через safe view. Для добавления evidence используется объединение множеств под row lock; существующее evidence не удаляется при RETRACTED. История сохраняет основание перехода. Оператор меняет status/history через control, detector меняет analysis_state/history; CAS revision и trigger запрещают перезапись истории.

### Независимая оценка

В reports.body case effect и invariant verdicts отдельны. Episode result: DETECTED/MISSED для malicious, FALSE_ALERT/NO_ALERT для benign, UNASSESSED при неполноте. FN — результат episode без искусственного incident. incidentAssessments содержит TP/FP/UNASSESSED, matchedEpisodeIds и независимый confirmedImpact=CONFIRMED/NOT_CONFIRMED/UNKNOWN; detector эти сведения не получает.

Evaluator проверяет принадлежность case/episode/incident/snapshot эксперименту, run и partition отчета. Для COMPLETE обязателен snapshot; при ошибке снимка сохраняется INCONCLUSIVE с причиной. SQL/JSON-ссылки не подменяют эту проверку. UI получает списки из JSONB выбранного report и может фильтровать локально/через jsonb_to_recordset; отдельный аналитический warehouse для MVP не нужен.

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

### `control.runner_jobs`

Lease и назначение runner; не SQL-доступ runner.

PK: `id`.
UNIQUE: `run_id, attempt`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `id` | `uuid` | нет | — | Job UUID |
| `run_id` | `uuid` | нет | — | Run; FK → `control.runs.id` |
| `epoch` | `uuid` | нет | — | Управляющая эпоха задания |
| `attempt` | `integer` | нет | — | Номер назначения |
| `plan` | `jsonb` | нет | — | Исполнимый план без внешних адресов |
| `plan_hash` | `bytea` | нет | — | SHA-256 плана |
| `lease_token_hash` | `bytea` | нет | — | Hash job credential; без исходного секрета |
| `lease_expires_at` | `timestamptz` | нет | — | Реальный deadline lease |
| `state` | `varchar(16)` | нет | — | PREPARED / RUNNING / STOPPED / FAILED |
| `step_manifest` | `jsonb` | да | — | Финальный manifest выполнения плана |
| `step_manifest_hash` | `bytea` | да | — | SHA-256 финального manifest |
| `sealed_at` | `timestamptz` | да | — | Реальное время фиксации manifest |

Общие CHECK: `attempt > 0`; `state IN ('PREPARED','RUNNING','STOPPED','FAILED')`; `octet_length(plan_hash) = 32`; `octet_length(lease_token_hash) = 32`; `(step_manifest IS NULL) = (step_manifest_hash IS NULL)`; `(step_manifest IS NULL) = (sealed_at IS NULL)`; `step_manifest_hash IS NULL OR octet_length(step_manifest_hash) = 32`.

### `control.attempts`

Один реальный HTTP-attempt, включая retry и timeout.

PK: `request_id`.
UNIQUE: `job_id, step_id, attempt_no`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `request_id` | `uuid` | нет | — | Gateway UUID; при недостижении gateway назначается harness |
| `step_id` | `uuid` | нет | — | Step UUID внутри immutable runner_jobs.plan; логическая ссылка |
| `attempt_no` | `integer` | нет | — | Номер попытки |
| `started_at` | `timestamptz` | нет | — | Реальное время |
| `finished_at` | `timestamptz` | да | — | Ответ/timeout |
| `http_status` | `integer` | да | — | NULL при транспортной ошибке |
| `transport_outcome` | `text` | нет | — | RESPONSE / TIMEOUT / ERROR |
| `response_evidence` | `jsonb` | нет | — | Allowlist полей; без tokens, secrets, raw body |
| `job_id` | `uuid` | нет | — | Назначение runner; FK → `control.runner_jobs.id` |

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

### `audit.producer_epochs`

Доверенная привязка producer/run; после SEAL также immutable manifest и coverage.

PK: `id`.
UNIQUE: `run_id, producer_kind`; `id, run_id, partition_id`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `id` | `uuid` | нет | — | Producer epoch UUID |
| `run_id` | `uuid` | нет | — | Run; FK → `control.runs.id` |
| `partition_id` | `uuid` | нет | — | Opaque partition |
| `producer_kind` | `text` | нет | — | GATEWAY / SHOP |
| `source` | `text` | нет | — | Доверенный CloudEvents source |
| `state` | `text` | нет | `'OPEN'` | OPEN / CLOSED / SEALED |
| `registered_at` | `timestamptz` | нет | — | Реальное время |
| `expected_event_ids` | `uuid[]` | да | — | Точный отсортированный уникальный набор; NULL до SEAL |
| `event_set_hash` | `bytea` | да | — | SHA-256 канонического списка UUID |
| `coverage` | `jsonb` | да | — | Accepted/terminal counts, request slots и reconciliation evidence |
| `sealed_at` | `timestamptz` | да | — | Реальное время SEAL |

Общие CHECK: `producer_kind IN ('GATEWAY','SHOP')`; `state IN ('OPEN','CLOSED','SEALED')`; `(state = 'SEALED' AND expected_event_ids IS NOT NULL AND event_set_hash IS NOT NULL AND coverage IS NOT NULL AND sealed_at IS NOT NULL) OR (state <> 'SEALED' AND expected_event_ids IS NULL AND event_set_hash IS NULL AND coverage IS NULL AND sealed_at IS NULL)`; `event_set_hash IS NULL OR octet_length(event_set_hash) = 32`; `expected_event_ids IS NULL OR cardinality(expected_event_ids) <= 100000`.

### `audit.security_events`

Append-only CloudEvents; одинаковый id с другим payload — ошибка.

PK: `id`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `id` | `uuid` | нет | — | Глобальный Event UUID |
| `producer_epoch` | `uuid` | нет | — | Зарегистрированный producer; FK → `audit.producer_epochs.id` |
| `run_id` | `uuid` | нет | — | Доверенная привязка run; FK → `control.runs.id` |
| `partition_id` | `uuid` | нет | — | Доверенная opaque partition |
| `request_id` | `uuid` | нет | — | HTTP-attempt |
| `trace_id` | `char(32)` | нет | — | Trace context |
| `occurred_at` | `timestamptz` | нет | — | Реальное время наблюдения |
| `received_at` | `timestamptz` | нет | `clock_timestamp()` | Время приема |
| `payload` | `jsonb` | нет | — | Валидированный CloudEvents v1 |
| `payload_hash` | `bytea` | нет | — | SHA-256 canonical payload |

Общие CHECK: `octet_length(payload_hash) = 32`; `jsonb_typeof(payload) = 'object'`.

Дополнительные индексы: `(partition_id, occurred_at, id)`; `(producer_epoch)`; `(request_id)`; `(run_id)`.

### `audit.receipts`

Gateway durable admission; terminal slot обязателен независимо от HTTP-ответа.

PK: `request_id`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `request_id` | `uuid` | нет | — | Принятый HTTP-attempt |
| `producer_epoch` | `uuid` | нет | — | Gateway epoch; FK → `audit.producer_epochs.id` |
| `started_event_id` | `uuid` | нет | — | Durable started event; FK → `audit.security_events.id` |
| `terminal_event_id` | `uuid` | да | — | Completed/abort evidence; FK → `audit.security_events.id` |
| `shop_invoked` | `boolean` | нет | `false` | Downstream мог быть вызван; отмечается до вызова |
| `accepted_at` | `timestamptz` | нет | — | Реальное время приема |
| `completed_at` | `timestamptz` | да | — | Время доказанного terminal outcome |

Общие CHECK: `(terminal_event_id IS NULL) = (completed_at IS NULL)`.

Дополнительные индексы: `(producer_epoch)`; `(started_event_id)`; `(terminal_event_id)`.

### `detection.partitions`

Без run/target/labels; lease с fencing token.

PK: `id`.
UNIQUE: `id, ruleset_hash`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `id` | `uuid` | нет | — | Opaque partition UUID; без FK на control |
| `ruleset_hash` | `bytea` | нет | — | Hash зафиксированного ruleset |
| `ruleset_snapshot` | `jsonb` | нет | — | Полные разрешенные правила и engine version |
| `lease_owner` | `uuid` | да | — | Worker instance |
| `lease_until` | `timestamptz` | да | — | Реальное время истечения |
| `fence` | `bigint` | нет | `0` | Монотонный fencing token |

Общие CHECK: `octet_length(ruleset_hash) = 32`; `fence >= 0`; `(lease_owner IS NULL) = (lease_until IS NULL)`.

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
