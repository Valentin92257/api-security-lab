# Сценарии, испытания и оценка

Нормативные источники: [Control API](../contracts/control-api.openapi.yaml), [типы Control](../contracts/schemas/control.yaml), [порты модулей](../contracts/modules.md) и [общие правила](../contracts/semantics.md).

## Угрозы и гарантии

Стенд моделирует клиента локального API с синтетическими credentials и объектами. Активы: учётные записи/JWT, профили/заказы, каталог/цены, корзина/купоны, access-логи и независимый ground truth. Runtime-компрометация Docker host/DBA не входит в модель. Генератор работает по конечному каталогу сценариев и встроенным targets.

| Case | Действие и критерий эффекта атакующего | Защита и benign-контроль |
|---|---|---|
| CS-01 | Серия пар login/password; полученный token действительно открывает профиль жертвы | IP/account limits/backoff; небольшое число ошибочных и корректный легитимный вход |
| CS-02 | Первая корректная пара даёт рабочую сессию жертвы | Явный остаточный риск парольной auth; отсутствие блокировки допустимо |
| BO-01 | Подмена orderId раскрывает чужой заказ | Owner-check/404; свой и отсутствующий order |
| BO-02 | Подмена userId раскрывает чужой профиль | Owner-check/404; свой и отсутствующий userId |
| RP-01 | Тот же key/body checkout создаёт дополнительный эффект | Cached response; технический retry без нового эффекта |
| RP-02 | Новый key с уже оформленной cartRevision или прежний key с иным body принят как новая операция | UNIQUE(cart_id, cart_revision)/fingerprint; новая законная версия корзины |
| IJ-01 | SQL-подобный q раскрывает draft canary вне нормативной выдачи | Параметризованный SQL/allowlist; обычный поиск, апострофы и буквальные %/_ |
| BL-01 | Expired/exhausted/repeated coupon даёт незаконную скидку или превышает лимит | Проверка срока/остатка в commit; допустимый купон и equality boundary expiry |
| BL-02 | Quantity 0/отрицательное/выше границы сохраняется или влияет на заказ | Schema/domain/constraints; quantity 1 и 99 |
| BL-03 | Cart race создаёт два order на одну revision; coupon race превышает остаток | Locks/unique/conditional update; два независимых допустимых заказа |
| BL-04 | Заниженный quotedTotal принят как цена заказа | Серверный расчёт/PRICE_CHANGED; корректная цена |

BL-01 имеет отдельные expired/exhausted/repeated-apply subcases; BL-03 - cart race и shared-coupon race разных пользователей. Каждый получает собственный результат. Injection включает один реальный изолированный SQL-кейс; NoSQL/command-подобные строки проверяются как отрицательный ввод, без отдельных runtime-движков.

CS-01: 200 сгенерированных попыток образуют один detection episode; три аккаунта с корректными парами - три case достижения доступа. Неверные пары создают контекст кампании, но не добавляют 197 заведомых неуспехов в знаменатель ASR. RP-01 содержит отдельно malicious попытку повторного эффекта и benign retry. Их намерение может быть неразличимо по HTTP; это ограничение отражается в метриках, а не устраняется передачей label detector.

Секретность id не заменяет owner-check; ошибка 500 не доказывает injection; JWT остаётся многократным. Украденный bearer позволяет новые допустимые операции. Rate policy не обещает остановить первую корректную пару, а правила detector покрывают только описанные признаки. Эти ограничения учитываются при интерпретации результатов и документируются вместе с измерениями.

Подготовка сценариев использует сокращённый API: login, GET профиля по ID, поиск products, GET cart, PUT /cart/items/{productId}, PUT /cart/coupon, POST /orders и GET заказа по ID. PUT количества создаёт или заменяет позицию; нулевое количество - отрицательный BL-02, не удаление. BO-01/BO-02 получают известные ID из приватного manifest; CS подтверждает доступ собственным профилем по ID. Для независимых случаев с пустой корзиной/без купона выделяются fixture-пользователи; удаления не нужны, отдельная ручка регистрации проверяется контрактными тестами и не меняет исходный fixture manifest. Повторный выбор купона проверяет BL-01; чтение cart после выбора не должно показывать накопление скидки. BL-03 использует разные key одной cartRevision либо общий купон разных пользователей.

## Модель испытания

| Сущность | Определение |
|---|---|
| Trial | Неизменяемый выбор families и seed; включает последовательные vulnerable/protected runs |
| Run | Один режим с чистой фикстурой и собственными runtime/analysis context |
| Case / subcase | Цель атакующего с независимым критерием эффекта |
| Episode | Заранее ограниченная последовательность; единица recall и benign FPR |
| Step | Действие плана с actor, clientIp и возможными retries |
| Attempt | Одна HTTP-отправка с UUID requestId, созданным до запроса |

План и FixtureData приватны для Control. Ресурсы, actors/owners, ATTACK/BENIGN, attack-specific шаги и независимые критерии задаются до старта. Пароли/JWT доступны Runner в памяти, но не попадают в attempts, логи и отчёт. Detector получает только runId и access-логи.

GET `/control/v1/scenarios` возвращает пять семейств. POST `/control/v1/trials` с Idempotency-Key создаёт и сразу запускает испытание:

```json
{"families":["BOLA","REPLAY"],"seed":42}
```

families содержит от одного до пяти уникальных значений; выбор всех пяти запускает весь набор. Seed необязателен: Control выбирает его и возвращает в trial. Оба режима обязательны, профили фиксированы, baseUrl/actors/policies/executable code в запросе отсутствуют. Тот же ключ/body возвращает прежний trial, другой body или занятый слот - 409.

## Runner и подготовка данных

Fixture - модуль Control с прямым административным доступом к shop DB. Он готовит данные при закрытых runtimes; Shop/Gateway не предоставляют API наполнения БД. В paired runs одинаковы seed, FixtureData и businessTimeBase, но бизнес-БД и состояние политик раздельны.

Runner также работает внутри Control: prepare/start/cancel и AttemptSink - Java-порты. Отдельный executor и конечные бюджеты не блокируют UI. clientIp шага передаётся в X-Forwarded-For; один виртуальный клиент сохраняет адрес в обоих режимах. Каждый retry получает новый requestId, повтор checkout - тот же Idempotency-Key/body.

ScenarioHttpClient разрешает только встроенные входы Gateway и бизнес-маршруты, не произвольные URL, redirects, proxy, SQL или management. Runner не получает datasource/Fixture/Evaluator через свои порты. Это граница доверенного кода общей JVM, не изоляция от злонамеренного Java-кода.

AttemptSink сохраняет результаты HTTP-запросов по [внутреннему порту](../contracts/modules.md) до completion Runner. Ошибка сохранения или отсутствие ожидаемого результата делает оценку неполной. Cancel/deadline прекращают новые шаги и retries; отмена HTTP Future не доказывает остановку SQL в Shop.

## Управление и завершение

| Метод | Назначение |
|---|---|
| GET `/control/v1/trials` | Пагинированный список |
| GET `/control/v1/trials/{trialId}` | Состояние, общий прогресс и cleanupSafe |
| POST `/control/v1/trials/{trialId}/cancel` | Остановка новых шагов с безопасным завершением активных операций |
| GET `/control/v1/trials/{trialId}/report` | Общий отчёт, до сохранения 409 |

Нормальные состояния Trial: PREPARING → RUNNING → FINALIZING → COMPLETED. Терминальные альтернативы: FAILED, CANCELLED, INCONCLUSIVE. Отмена повторяема; настройки trial не редактируются. При cleanupSafe=false нельзя готовить новую БД или запускать следующее испытание.

Для каждого run Control готовит БД, контексты Shop/Gateway и анализ Detector. Открывает Shop, затем Gateway. После завершения Runner:

1. Дождаться сохранения attempts и прекращения новых отправок.
2. Закрыть Gateway и дождаться CLOSED, затем закрыть Shop. У каждого inFlight=0 и окончательный lastSequence.
3. Передать оба lastSequence и deadlineAt в Detector finishAnalysis.
4. Дождаться COMPLETED; сверить observedRequestIds с attempts, получить итоговые incidents.
5. Снять readonly состояние, выполнить evaluator и сохранить результаты. Только после безопасного завершения возможна следующая подготовка.

Detector имеет отдельные состояния ACCEPTING → FINISHING → COMPLETED либо FAILED. Runtime: PREPARED → OPEN → CLOSING → CLOSED либо ERROR. Произвольный sleep не заменяет эти проверки. Потеря логов, смена instanceId, недоступный baseline и deadline дают INCONCLUSIVE. Прерванный run не возобновляется; выполненные транзакции не откатываются.

Штатный порядок режимов всегда vulnerable, затем protected. Seed не фиксирует SQL/OS interleaving: конкурентные cases используют barriers и явно учитывают повторы. При отмене/ошибке второй run может отсутствовать; он не считается выполненным. Отчёт содержит доступные результаты.

## Независимый эталон

Evaluator использует исходный snapshot, план, HTTP responseFacts, readonly состояние Shop и итоговые incidents. Он не вызывает production calculator/rule engine и не принимает HTTP BLOCK или incident как доказательство предотвращения.

Case effect: EFFECT_OBSERVED / EFFECT_NOT_OBSERVED / INCONCLUSIVE. Для BOLA проверяются возвращённые чужие поля; для login - пригодность токена; для replay/race - число эффектов; для injection - draft canary; для бизнес-логики - соответствующий инвариант. TIMEOUT/ошибка инфраструктуры сами по себе не означают отсутствие эффекта.

Control связывает incident с episode по атакующим requestIds и эталону. Семейство в incident необязательно; ошибочная классификация поясняется в reason отдельно от факта обнаружения. Несколько incidents дают один обнаруженный episode. Benign retry остаётся benign, даже если Detector ошибочно объявил его атакой.

## Метрики и отчёт

| Метрика | Формула |
|---|---|
| ASR | Успешные / оценённые attack cases |
| Precision | TRUE_POSITIVE / (TRUE_POSITIVE + FALSE_POSITIVE) incidents |
| Recall | Обнаруженные / оценённые attack episodes |
| FPR | Benign episodes с ложным incident / оценённые benign episodes |

Заблокированные попытки входят в обнаружение. INCONCLUSIVE/UNASSESSED исключаются из соответствующих знаменателей; 0/0 показывается как N/A. При незавершённом анализе incidents=null, а detection у cases - INCONCLUSIVE, а не пустой успешный результат. Недостаток наблюдений нельзя объявлять пропуском детектора.

TrialReport содержит trialId, families, seed, state и runs. Каждый RunReport содержит runId, mode, state, cases, incidents и необязательный текст error. CaseResult объединяет effect и detection; episodeId группирует одну кампанию. family, label и detection одинаковы внутри группы. Incidents включают оценку Control; метрики дашборд вычисляет из результатов, считая уникальные episodeId для recall/FPR. У отчёта нет отдельной revision, редактирования incidents или HTTP-метода сравнения: оба режима уже находятся в одном JSON. Успешное парное сравнение требует двух полноценно завершённых runs; частичные результаты показываются с причинами.
