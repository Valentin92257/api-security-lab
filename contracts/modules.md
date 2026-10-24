# Модули внутри Control

Вызовы внутри одного процесса. Типизированные порты и внутренние records находятся в [ControlPorts.java](../apps/lab-control/src/main/java/lab/control/ControlPorts.java). Они не являются HTTP/JSON API и не генерируются из OpenAPI. Используются enum и Incident/RunReport из существующего Control API, без зависимости от приложений Shop/Detector.

| Вызов | Вход | Результат |
|---|---|---|
| Fixture.prepare | mode, seed, businessTimeBase | FixtureData: исходные данные и credentials в памяти |
| Runner.prepare | runId, mode, families, seed, FixtureData, deadlineAt | PreparedRun с неизменным plan() |
| PreparedRun.start | ScenarioHttpClient, AttemptSink | CompletionStage после сохранения всех отправленных attempts |
| PreparedRun.cancel | - | Идемпотентно останавливает новые шаги/retries; текущие запросы завершаются |
| AttemptSink.append | runId, Attempt | Возврат только после сохранения; одинаковый requestId/content безопасен, конфликт - ошибка |
| Evaluator.evaluate | runId, mode, plan, baseline, attempts, readonly Shop, observedRequestIds, incidents | RunReport; эффект по БД/ответам, обнаружение по итоговым инцидентам |

## Передаваемые данные

- FixtureData содержит Actor (id, login/password, name/email, cartId/cartRevision), Product (id, name/description, price, published), Coupon (id, code, discountPercent, expiresAt, maxUses/usedCount), Order (id, userId, cartId/cartRevision, couponId). Исходные корзины пустые, без купона; остальные строки задаются [фикстурой v1](../docs/02-api-and-data.md). Коллекция orders задаёт исходные ID/число заказов, usedCount - исходный счётчик купона. Полного DTO заказа здесь не нужно.
- CasePlan содержит caseId/episodeId, family, label, actors, resources, steps и evidenceSteps. Это метаданные плана, без отдельного DSL выполнения. caseId - код проверки из [каталога](../docs/05-scenarios-and-experiments.md); roles в actors (`attacker`, `victim`, `peer`) ссылаются на Actor.id; resources использует ключи `order`, `cart`, `product`, `coupon` с ID соответствующей таблицы. Неиспользуемые роли/ресурсы отсутствуют. steps - ожидаемые уникальные ID шагов внутри case; evidenceSteps - их подмножество, подтверждающее проверяемое действие. Подготовительные login/cart в него не включаются. Конкретные запросы строит Runner по каталогу, seed и этим данным; Evaluator не использует исполняющий код Runner.
- Attempt связывает caseId/stepId с requestId каждой отправки, businessTime, transportOutcome, status и responseFacts. Retry получает новый requestId и прежний stepId. status обязателен только для RESPONSE, иначе null. businessTime - бизнес-время отправки для проверки срока купона; время выполнения JWT остаётся реальным.
- responseFacts - безопасное JSON-тело ответа Shop. У login поле accessToken заменяется на `tokenReceived: boolean`; сам JWT остаётся в памяти Runner и проверяется последующим запросом профиля. Невалидный JSON/отсутствие ответа дают null и не служат доказательством эффекта. Пароли, хеши, JWT и исходные тела запросов не сохраняются. Такой формат сохраняет поля профиля/заказа и список товаров для независимого oracle без нового семейства DTO.

FixtureData и план неизменны после prepare, живут в памяти Control и не экспортируются. Стандартный toString этих records не используется в логах: Actor содержит пароль. Состав data и выбранные ID совпадают между режимами; бизнес-время начинается с одинакового businessTimeBase. Синтетический clientIp воспроизводится Runner по seed/Actor.id и остаётся одинаковым в обоих режимах.

ScenarioHttpClient принимает только адреса Gateway текущего режима, проверяет также redirect и не предоставляет произвольный сетевой доступ. Runner устанавливает X-Request-Id/X-Forwarded-For на каждой отправке, хранит JWT только в памяти и не получает DataSource. Fixture получает административный доступ через конфигурацию своей реализации; Evaluator - только readonly DataSource и отдельный приватный baseline.

PreparedRun.start вызывается один раз; завершение означает, что все начатые запросы получили Attempt и сохранены. Ошибка сохранения завершает stage с ошибкой. cancel до start не запускает запросов. Отсутствующие steps и неподтверждённые отправки остаются INCONCLUSIVE, не становятся отрицательным результатом. Control закрывает runtimes, получает итоговый анализ, затем вызывает Evaluator. При недоступном анализе observedRequestIds/incidents равны null; при завершённом - непустые или пустые коллекции. Evaluator возвращает COMPLETED либо INCONCLUSIVE по полноте данных; Control выставляет FAILED/CANCELLED при соответствующей причине остановки, сохраняя частичные результаты. Поля обнаружения рассчитываются по [общим правилам](semantics.md).
