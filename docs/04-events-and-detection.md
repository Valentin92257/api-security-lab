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

Detector view строится из whitelist полей, без raw JSON. В ней отсутствуют scenario/case/step ids, expected outcome, fixture labels, target mode/backend name, source URI, policy mode/snapshot hash и oracle results. RequestId/traceId разрешены для join/evidence/dedupe, partition — для изоляции состояния, но не как классификационные признаки YAML. SQL-role не имеет доступа к исходным audit/control/shop таблицам. Для CLOSE получает только безопасный список event IDs в `detection.job_events` и его hash.

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
