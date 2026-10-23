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
| Завершение | JSONL policy facts, затем разрешённый proxy и terminal log | Обязательная обвязка chain |

Gateway пишет request.started до проверок и policy.evaluated для каждой политики, включая ранние 400/401/413/429. До downstream должны быть записаны start/решение; gateway не ждёт collector или lab DB. При ошибке записи логов закрывается admission, возвращается 503 DEPENDENCY_UNAVAILABLE и run получает INCONCLUSIVE. Terminal request.completed пишется после результата, с downstreamInvoked=true/false. Если результат уже изменил данные, ошибка последующей записи не откатывает commit.

Для основного сравнения vulnerable использует OBSERVE экспериментальных gateway-политик и учебные shop-стратегии; protected — ENFORCE и защищённые стратегии. Проверка JWT, ресурсные пределы, audit и изоляция одинаковы. Gateway mode не переключает owner-check/SQL constraints внутри shop.

## Сценарный трафик из control

Генератор исполняется внутри lab-control, но все бизнес-запросы по-прежнему проходят через gateway. ScenarioHttpClient выбирает только встроенный target/route. Короткоживущий run credential связывает harness context с runId/epoch/target и разрешает доверенную корреляцию attemptId/requestId; он не даёт operator/admin прав и не подменяет пользовательский JWT. Gateway проверяет соответствие активному admission и listener; клиентские Host/query/headers не выбирают backend. Срок credential и закрытие admission ограничивают старый трафик после завершения run.

Сценарный модуль не получает admin-клиент или SQL datasource через свои интерфейсы. Однако у общей JVM control такие полномочия есть: изоляция модуля проверяется архитектурными тестами, а не обещается сетевым sandbox. Внешний egress закрыт для всего контейнера control.

## Credential stuffing

Готовый limiter Bucket4j in-memory использует независимые ключи source и HMAC нормализованного login. Account key вычисляется одинаково для существующих и отсутствующих аккаунтов. Входная проверка расходует бюджет попытки до обращения к shop; результат проверки пароля поступает только от доверенного shop и обновляет ограниченный backoff/временную блокировку.

TTL, account/source thresholds, максимальная задержка, размер очереди и concurrency фиксируются до run. Backoff не создаёт неограниченное число ожидающих потоков. Namespace состояния: `(opaquePartitionId, policyVersion, mode, source/account key)`. Прогоны и targets не наследуют блокировки друг друга. SourceKey выдаёт control для моделирования источников; произвольный X-Forwarded-For не меняет его.

При переполнении бюджета ключей или внутренней ошибке limiter gateway закрывает admission и возвращает 503/INFRA_REJECT независимо от mode; run INCONCLUSIVE. Активные ограничения не вытесняются. Такой отказ отличим от осмысленного BLOCK. Успешный вход с первой корректной парой остаётся возможным: limiter ограничивает последовательность, но не распознаёт факт кражи корректного пароля.


Состояние limiter живёт только в одном экземпляре gateway: локальные Bucket4j buckets и потокобезопасный учёт auth failures/backoff. Ключ включает `(opaquePartitionId, policyVersion, mode, source/account key)`. OBSERVE и ENFORCE не разделяют счётчики; OFF их не изменяет. Состояние не записывается в PostgreSQL и не передаётся между репликами; несколько gateway-реплик не поддерживаются.

PREPARE при закрытом admission и отсутствии старых in-flight создаёт чистое состояние нового run. Результат старого запроса не обновляет новый namespace: callback проверяет run/epoch. После DRAINING состояние завершённого run удаляется. Перезапуск gateway теряет локальные счётчики, поэтому текущий опыт INCONCLUSIVE и не продолжается; новый опыт начинается с фикстур и чистых политик.

Начальный настраиваемый бюджет — 10000 ключей суммарно на run, фиксируется в policy snapshot до старта. TTL очищает только неиспользуемые записи, у которых закончился backoff и bucket полностью восстановился, без активной обработки. Нельзя удалять действующее ограничение ради освобождения места и выдавать полный bucket. Атомарность consume/update/cleanup при параллельных запросах проверяется тестами; блокировка ключа не держится во время вызова shop. Лимит памяти/числа ключей и ошибка состояния дают infrastructure reject и INCONCLUSIVE, без тихого сброса ограничений.

## Прикладные гарантии

Protected использует authenticated subject в owner query; чужой и отсутствующий объект возвращают одинаковый 404. Catalog SQL параметризован, sort выбирается из enum, datasource имеет только SELECT товаров. Schema validation не заменяет ни owner-check, ни SQL-параметризацию.

Checkout фиксирует исходную версию корзины и поля идемпотентности заказа в одной транзакции с заказом, корзиной и купоном; JSONL-событие результата пишется после commit. Повтор успешного key/body возвращает прежний Order; новый key с уже оформленной cartRevision и изменённое тело прежнего key получают conflict. Access JWT многократный: защита одного бизнес-эффекта не запрещает владельцу украденного bearer выполнять новые допустимые операции.

Сервер рассчитывает цены/скидки, проверяет quantity, cart revision, expiry и coupon limit. Locks/conditional update/constraints совместно обеспечивают один допустимый commit. Client timeout и ошибка зависимости сами по себе не означают, что атака предотвращена.

## Конфигурация и измерение

PolicySnapshot включает id/version/hash, mode, порядок, thresholds, TTL, failure policy и ресурсные бюджеты. Он валидируется до PREPARING и неизменяем в run; изменение создаёт новую конфигурацию и новый run. Первоначальные thresholds калибруются на benign workload; пользовательские значения ограничены разрешёнными диапазонами.

Журнал сохраняет все evaluations и одну фактическую причину остановки, различая gateway reject, shop reject и infrastructure error. Для overhead OFF/OBSERVE/ENFORCE сравниваются на одном backend и одинаковом benign workload. WouldBlock из OBSERVE описывает реально наблюдавшийся поток: после предполагаемой блокировки дальнейшее поведение могло бы измениться, поэтому это не точный прогноз результата ENFORCE.
