# Защита и gateway

## Распределение проверок

Gateway обеспечивает обязательный вход через два фиксированных listener: 8081 для vulnerable, 8082 для protected. Он проверяет JWT, форму запросов, бюджет и rate policy. Shop проверяет владельца ресурса, параметризует SQL и защищает бизнес-транзакции. Gateway не читает владельцев заказов и не расходует купоны.

Пользователь не может выбрать backend, owner, role или решение политики через заголовок. runId берётся из подготовленного Runtime-контекста. X-Request-Id создаётся генератором до отправки и сохраняется Gateway/Shop; X-Forwarded-For содержит один виртуальный IP, которому Gateway доверяет только от генератора, Shop - только от Gateway. Management listener защищён отдельным ServiceBearer.

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
| mode | OBSERVE - вычислять без блокировки; ENFORCE - применять решение; OFF возможен только как внутренний технический baseline, не режим текущего API |
| evaluationStatus | EVALUATED, SKIPPED, NOT_APPLICABLE, NOT_REACHED, MISSING_CONTEXT, ERROR |
| wouldDecision | ALLOW/BLOCK/FLAG только при EVALUATED, иначе null |
| enforcedAction | CONTINUE, REJECT, INFRA_REJECT - фактическое действие |

FLAG продолжает запрос с отметкой; incident создаёт detector. OBSERVE может иметь wouldDecision=BLOCK при фактическом CONTINUE. После первой блокировки последующие экспериментальные проверки получают NOT_REACHED. Состояние shadow counters отделено от ENFORCE.

| Порядок | Механизм | Режим |
|---|---|---|
| 0 | Admission активного runId, ограничения размера/времени/параллельности | Обязательный |
| 10 | JWT: fixed alg, signature, exp/nbf, issuer/audience; register/login освобождены от bearer | Обязательный |
| 20 | OpenAPI: маршрут, media type, типы, required, bounds, неизвестные поля/query | OBSERVE/ENFORCE |
| 30 | Login limits по source/account и backoff | OBSERVE/ENFORCE |
| 40 | Дополнительные категории подозрительного ввода | Необязательный сигнал; не замена SQL-параметризации |
| Завершение | Разрешённый proxy, обработка ошибки и одна итоговая access-запись | Обязательная обвязка chain |

Gateway формирует одну итоговую access-запись на запрос, включая ранние 400/401/413/429, после определения реального status. Решения PolicyChain остаются внутренней диагностикой и не входят в AccessLog. При ошибке сохранения обязательного лога закрывается admission, run получает INCONCLUSIVE. Если результат уже изменил данные, ошибка последующей записи не откатывает commit.

Для основного сравнения vulnerable использует OBSERVE экспериментальных gateway-политик и учебные shop-стратегии; protected - ENFORCE и защищённые стратегии. Проверка JWT, ресурсные пределы, access-логирование и изоляция одинаковы. Gateway mode не переключает owner-check/SQL constraints внутри shop.

## Сценарный трафик из control

Генератор исполняется внутри lab-control, но все бизнес-запросы проходят через Gateway. ScenarioHttpClient выбирает только Gateway текущего режима. X-Request-Id связывает HTTP, attempts, логи и incidents; каждый retry получает новый UUID. runId задаётся Runtime API, X-Lab-заголовки не используются. Gateway проверяет OPEN и listener; клиентские Host/query/headers не выбирают backend. После CLOSED admission не открывается снова для того же runId.

Сценарный модуль не получает admin-клиент или SQL datasource через свои интерфейсы. Однако у общей JVM control такие полномочия есть: изоляция модуля проверяется архитектурными тестами, а не обещается сетевым sandbox. Внешний egress закрыт для всего контейнера control.

## Credential stuffing

Готовый limiter Bucket4j in-memory использует независимые ключи source и HMAC нормализованного login. Account key вычисляется одинаково для существующих и отсутствующих аккаунтов. Входная проверка расходует бюджет попытки до обращения к shop; результат проверки пароля поступает только от доверенного shop и обновляет ограниченный backoff/временную блокировку.

TTL, account/source thresholds, максимальная задержка, размер очереди и concurrency фиксируются до run. Backoff не создаёт неограниченное число ожидающих потоков. Namespace состояния: `(runId, policyVersion, mode, source/account key)`. Прогоны и targets не наследуют блокировки друг друга. Источник - IP виртуального клиента из доверенного X-Forwarded-For; недоверенный заголовок игнорируется, используется адрес соединения.

При переполнении бюджета ключей или внутренней ошибке limiter gateway закрывает admission и возвращает 503/INFRA_REJECT независимо от mode; run INCONCLUSIVE. Активные ограничения не вытесняются. Такой отказ отличим от осмысленного BLOCK. Успешный вход с первой корректной парой остаётся возможным: limiter ограничивает последовательность, но не распознаёт факт кражи корректного пароля.


Состояние limiter живёт только в одном экземпляре gateway: локальные Bucket4j buckets и потокобезопасный учёт auth failures/backoff. Ключ включает `(runId, policyVersion, mode, source/account key)`. OBSERVE и ENFORCE не разделяют счётчики; OFF их не изменяет. Состояние не записывается в PostgreSQL и не передаётся между репликами; несколько gateway-реплик не поддерживаются.

PREPARE при закрытом admission и отсутствии старых in-flight создаёт чистое состояние нового run. Результат старого запроса не обновляет новый namespace: callback проверяет runId. После безопасного завершения состояние завершённого run удаляется. Перезапуск gateway теряет локальные счётчики, поэтому текущий опыт INCONCLUSIVE и не продолжается; новый опыт начинается с фикстур и чистых политик.

Начальный настраиваемый бюджет - 10000 ключей суммарно на run, фиксируется в policy snapshot до старта. TTL очищает только неиспользуемые записи, у которых закончился backoff и bucket полностью восстановился, без активной обработки. Нельзя удалять действующее ограничение ради освобождения места и выдавать полный bucket. Атомарность consume/update/cleanup при параллельных запросах проверяется тестами; блокировка ключа не держится во время вызова shop. Лимит памяти/числа ключей и ошибка состояния дают infrastructure reject и INCONCLUSIVE, без тихого сброса ограничений.

## Прикладные гарантии

Protected использует authenticated subject в owner query; чужой и отсутствующий объект возвращают одинаковый 404. Catalog SQL параметризован, порядок сортировки фиксирован по id, datasource имеет только SELECT товаров. Schema validation не заменяет ни owner-check, ни SQL-параметризацию.

Checkout фиксирует исходную версию корзины и поля идемпотентности заказа в одной транзакции с заказом, корзиной и купоном; итоговый access-лог формируется после результата и обработки HTTP-ошибки. Повтор успешного key/body возвращает прежний Order; новый key с уже оформленной cartRevision и изменённое тело прежнего key получают conflict. Access JWT многократный: защита одного бизнес-эффекта не запрещает владельцу украденного bearer выполнять новые допустимые операции.

Сервер рассчитывает цены/скидки, проверяет quantity, cart revision, expiry и coupon limit. Locks/conditional update/constraints совместно обеспечивают один допустимый commit. Client timeout и ошибка зависимости сами по себе не означают, что атака предотвращена.

## Конфигурация и измерение

PolicySnapshot включает id/version/hash, mode, порядок, thresholds, TTL, failure policy и ресурсные бюджеты. Он валидируется до PREPARING и неизменяем в run; изменение создаёт новую конфигурацию и новый run. Первоначальные thresholds калибруются на benign workload; пользовательские значения ограничены разрешёнными диапазонами.

Внутренняя диагностика Gateway может сохранять evaluations и фактическую причину остановки, различая gateway reject, shop reject и infrastructure error. Эти данные не входят в access-логи Detector и текущий TrialReport. WouldBlock из OBSERVE описывает наблюдавшийся поток: после предполагаемой блокировки дальнейшее поведение могло бы измениться, поэтому это не точный прогноз ENFORCE. Для текущего API фиксированы vulnerable/OBSERVE и protected/ENFORCE; отдельное сравнение overhead с OFF не входит в контракт.

Нормативные ограничения Shop API применяет protected-реализация. У генерации общего Shop API задано `useBeanValidation=false`, чтобы `@Valid`/`@Min` не блокировали учебные quantity/quotedTotal до выбора стратегии. JSON syntax, типы, JWT и ресурсные пределы остаются общими; Runtime и остальные API сохраняют Bean Validation.
