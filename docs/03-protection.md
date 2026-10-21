# Защита и gateway

## Распределение проверок

Gateway обеспечивает обязательный вход через два фиксированных listener: 8081 для vulnerable, 8082 для protected. Он проверяет JWT, форму запросов, бюджет и rate policy. Shop проверяет владельца ресурса, параметризует SQL и защищает бизнес-транзакции. Gateway не читает владельцев заказов и не расходует купоны.

Пользователь не может выбрать backend, owner, role или решение политики через заголовок. Harness-контекст run/step принимается только с отдельным credential и привязкой к активному assignment/epoch; downstream получает доверенный контекст. Management listener защищён отдельными полномочиями.

## PolicyChain

```java
interface GatewayPolicy {
    String id();
    int order();
    boolean appliesTo(RequestContext request);
    PolicyEvaluation evaluate(RequestContext request, PolicySnapshot snapshot);
}
```

PolicyEvaluation содержит policyId/version, evaluationStatus, wouldDecision, reasonCode, durationMs и разрешённые безопасные observations. HTTP-ответ формирует chain; исключение проверки не превращается в ALLOW.

| Поле | Семантика |
|---|---|
| mode | OFF — не вычислять; OBSERVE — вычислять без блокировки; ENFORCE — применять решение |
| evaluationStatus | EVALUATED, SKIPPED, NOT_APPLICABLE, NOT_REACHED, MISSING_CONTEXT, ERROR |
| wouldDecision | ALLOW/BLOCK/FLAG только при EVALUATED, иначе null |
| enforcedAction | CONTINUE, REJECT, INFRA_REJECT — фактическое действие |

FLAG продолжает запрос с отметкой; incident создаёт detector. OBSERVE может иметь wouldDecision=BLOCK при фактическом CONTINUE. После первой блокировки последующие экспериментальные проверки получают NOT_REACHED. Состояние shadow counters отделено от ENFORCE.

| Порядок | Механизм | Режим |
|---|---|---|
| 0 | Admission активного epoch, ограничения размера/времени/параллельности | Обязательный |
| 10 | JWT: fixed alg, signature, exp/nbf, issuer/audience; register/login освобождены от bearer | Обязательный |
| 20 | OpenAPI: маршрут, media type, типы, required, bounds, неизвестные поля/query | OFF/OBSERVE/ENFORCE |
| 30 | Login limits по source/account и backoff | OFF/OBSERVE/ENFORCE |
| 40 | Дополнительные категории подозрительного ввода | Необязательный сигнал; не замена SQL-параметризации |
| Завершение | Durable audit decisions, затем разрешённый proxy и запись его результата | Обязательная обвязка chain |

Audit выполняется также для коротких отказов 400/401/413/429: ранний BLOCK не пропускает запись. До downstream gateway сохраняет receipt и решения. Если audit DB недоступна, downstream не вызывается, ответ 503 DEPENDENCY_UNAVAILABLE. Ошибка финальной записи после downstream требует проверки бизнес-эффекта и пометки неполного измерения.

Для основного сравнения vulnerable использует OBSERVE экспериментальных gateway-политик и учебные shop-стратегии; protected — ENFORCE и защищённые стратегии. Проверка JWT, ресурсные пределы, audit и изоляция одинаковы. Gateway mode не переключает owner-check/SQL constraints внутри shop.

## Credential stuffing

Готовый limiter Bucket4j/Redis использует независимые ключи source и HMAC нормализованного login. Account key вычисляется одинаково для существующих и отсутствующих аккаунтов. Входная проверка расходует бюджет попытки до обращения к shop; результат проверки пароля поступает только от доверенного shop и обновляет ограниченный backoff/временную блокировку.

TTL, account/source thresholds, максимальная задержка, размер очереди и concurrency фиксируются до run. Backoff не создаёт неограниченное число ожидающих потоков. Namespace состояния: `(opaquePartitionId, policyVersion, mode, source/account key)`. Прогоны и targets не наследуют блокировки друг друга. SourceKey выдаёт control для моделирования источников; произвольный X-Forwarded-For не меняет его.

При отказе Redis ENFORCE возвращает 503/INFRA_REJECT. OBSERVE может продолжить с evaluationStatus=ERROR и недоступной метрикой политики. Оба случая отличимы от осмысленного BLOCK. Успешный вход с первой корректной парой остаётся возможным: limiter ограничивает последовательность, но не распознаёт факт кражи корректного пароля.

## Прикладные гарантии

Protected использует authenticated subject в owner query; чужой и отсутствующий объект возвращают одинаковый 404. Catalog SQL параметризован, sort выбирается из enum, datasource имеет только SELECT товаров. Schema validation не заменяет ни owner-check, ни SQL-параметризацию.

Checkout фиксирует одноразовый server-issued intent и поля идемпотентности заказа в одной транзакции с заказом, купоном и outbox. Повтор успешного key/body возвращает прежний Order; новый key со spent intent и изменённое тело прежнего key получают conflict. Access JWT многократный: защита одного бизнес-эффекта не запрещает владельцу украденного bearer выполнять новые допустимые операции.

Сервер рассчитывает цены/скидки, проверяет quantity, cart revision, expiry и coupon limit. Locks/conditional update/constraints совместно обеспечивают один допустимый commit. Client timeout и ошибка зависимости сами по себе не означают, что атака предотвращена.

## Конфигурация и измерение

PolicySnapshot включает id/version/hash, mode, порядок, thresholds, TTL, failure policy и ресурсные бюджеты. Он валидируется до PREPARING и неизменяем в run; изменение создаёт новую конфигурацию и новый run. Первоначальные thresholds калибруются на benign workload; пользовательские значения ограничены разрешёнными диапазонами.

Журнал сохраняет все evaluations и одну фактическую причину остановки, различая gateway reject, shop reject и infrastructure error. Для overhead OFF/OBSERVE/ENFORCE сравниваются на одном backend и одинаковом benign workload. WouldBlock из OBSERVE описывает реально наблюдавшийся поток: после предполагаемой блокировки дальнейшее поведение могло бы измениться, поэтому это не точный прогноз результата ENFORCE.
