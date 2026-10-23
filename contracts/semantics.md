# Общие правила

- Один trial содержит VULNERABLE и PROTECTED с одинаковыми seed и исходными данными; одновременно выполняется один trial.
- Gateway: JWT обязателен в обоих режимах; schema/login policies - OBSERVE/ENFORCE. Shop отвечает за владельцев, SQL и бизнес-правила. Login/email - trim/lowercase, coupon - trim/uppercase, name - trim; password не нормализуется.
- Генератор создаёт requestId до отправки, каждый retry получает новый UUID. Gateway передаёт runId/requestId/source в Shop и возвращает X-Request-Id в ответе. Run token проверяется отдельно от JWT, source входит в allowlist. Служебный bearer не является пользовательским JWT.
- Detector получает только runId и факты, без режимов, списка атак и эталона. Токены producers разрешают запись своей роли, токен Control - управление и чтение.

## Завершение прогона

1. Control готовит БД при закрытых runtimes, затем контексты Shop/Gateway, план и анализ Detector. Открывает Shop, затем Gateway.
2. После остановки runner закрывает Gateway и ждёт CLOSED, затем закрывает Shop. CLOSED означает отсутствие inFlight и окончательный lastSequence.
3. Передаёт оба lastSequence и deadlineAt в Detector. Тот принимает оставшиеся пачки, проверяет непрерывные 1..N и завершает анализ. ACK пачки означает сохранение, не окончание анализа.
4. После COMPLETED Control получает инциденты и сравнивает observedRequestIds с attempts. Отсутствие RESPONSE requestId означает неполноту. Только после сохранения отчёта можно сбрасывать БД.

Повторы prepare/open/close/finish безопасны; изменённые контекст или границы - 409. CLOSED не открывается заново. Log retries сохраняют eventId/sequence/content; конфликт - 409. Перезапуск instanceId, потеря логов и deadline дают INCONCLUSIVE; committed операции не откатываются. Новые запросы после закрытия не дописывают завершённый поток. Нельзя начинать новое испытание поверх незавершённых операций.

## События

У принятого запроса - start и один terminal после результата операции; отказ Gateway до Shop не создаёт Shop events. time - реальное UTC; businessTimeBase задаёт отдельные бизнес-часы. Неизвестные facts - null или отсутствуют. Пароли/JWT/raw body/query/сырые ключи не логируются. Hash логина/Idempotency-Key - lowercase hex HMAC-SHA256 с общим correlationKey прогона; login нормализован, ключ берётся исходный.

| Событие | Producer | Существенные facts |
|---|---|---|
| http started/completed | Gateway | В completed: downstreamInvoked |
| api started/completed | Shop | В completed: operationExecuted, stateChanged |
| policy evaluated | Gateway | policyId, evaluationStatus, policyDecision, enforcedAction, reasonCode |
| auth attempted | Shop | accountKey, authOutcome |
| object accessed | Shop | resourceType/Id, resourceOwnerId, subjectMatchesOwner |
| operation completed | Shop | operation, operationExecuted/stateChanged; checkout: cartId/revision, orderId, hash ключа, idempotencyOutcome |
| input observed | Shop | inputCategories; поиск также пишет известные returnedProductIds/containsUnpublishedProduct |

Полные имена событий и типы - в LogEvent/LogFacts. OBSERVE может иметь BLOCK при CONTINUE; у невычисленной политики decision=null. Cached checkout: REPLAYED и stateChanged=false. До 100 событий в пачке, 64KiB на событие, 8MiB на пачку, 100000 событий/run. Статусы анализа: ACCEPTING→FINISHING→COMPLETED либо FAILED. observedRequestIds заполняется только после COMPLETED; инциденты неизменяемы, до завершения их получение даёт 409.

## Результаты

Control независимо оценивает эффект case и верность incident. Совпадение семейства и атакующего requestId даёт TRUE_POSITIVE, включая заблокированные попытки. Неверное семейство/benign evidence - FALSE_POSITIVE; нехватка доказательств - UNASSESSED. Подготовительные login/cart не подтверждают атаку. Несколько инцидентов дают один обнаруженный episode; CS-01 - один episode на кампанию, три case доступа.

Сводки вычисляются по cases/episodes/incidents: ASR - успешные/оценённые attack cases, precision - TP/(TP+FP), recall - обнаруженные/оценённые attack episodes, FPR - ложноположительные/оценённые benign episodes. INCONCLUSIVE/UNASSESSED исключаются из соответствующих знаменателей; 0/0 показывается как N/A. При незавершённом анализе episodes/incidents=null, а не пустой успешный результат.

Общие ошибки: 400 - входные данные, 401/403 - доступ, 404 - неизвестный ресурс, 409 - конфликт/результат не готов. При создании trial одинаковые Idempotency-Key/body возвращают прежний trial до проверки занятого слота. Checkout того же key/body возвращает прежний 201/Order; изменённый body, старая revision, неверная цена/купон дают 409. После успешного protected checkout корзина очищается; vulnerable сохраняет её для replay.
