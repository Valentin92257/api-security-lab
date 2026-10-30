# Сценарии, эксперименты и оценка

## Угрозы и гарантии

Стенд моделирует клиента локального API с синтетическими credentials и объектами. Активы: учётные записи/JWT, профили/заказы, каталог/цены, корзина/купоны, доказательный audit и независимый ground truth. Runtime-компрометация Docker host/DBA не входит в модель. Генератор работает по конечному каталогу сценариев и встроенным targets.

| Case | Действие и критерий эффекта атакующего | Защита и benign-контроль |
|---|---|---|
| CS-01 | Серия пар login/password; полученный token действительно открывает профиль жертвы | Source/account limits/backoff; небольшое число ошибочных и корректный легитимный вход |
| CS-02 | Первая корректная пара даёт рабочую сессию жертвы | Явный остаточный риск парольной auth; отсутствие блокировки допустимо |
| BO-01 | Подмена orderId раскрывает чужой заказ | Owner-check/404; свой и отсутствующий order |
| BO-02 | Подмена userId раскрывает чужой профиль | Owner-check/404; свой и отсутствующий userId |
| RP-01 | Тот же key/body checkout создаёт дополнительный эффект | Cached response; технический retry без нового эффекта |
| RP-02 | Новый key с уже оформленной cartRevision или прежний key с иным body принят как новая операция | UNIQUE(cart_id, cart_revision)/fingerprint; новая законная версия корзины |
| IJ-01 | SQL-подобный q раскрывает draft canary вне нормативной выдачи | Параметризованный SQL/allowlist; обычный поиск, апострофы и буквальные %/_ |
| BL-01 | Expired/exhausted/repeated coupon даёт незаконную скидку или превышает лимит | Проверка срока/остатка в commit; допустимый купон и equality boundary expiry |
| BL-02 | Quantity 0/отрицательное/выше границы сохраняется или влияет на заказ | Schema/domain/constraints; quantity 1 и 99 |
| BL-03 | Cart race создаёт два order на одну revision; coupon race превышает остаток | Locks/unique/conditional update; два независимых допустимых заказа |
| BL-04 | Заниженный quotedTotal принят как цена заказа | Серверный расчёт/CONFLICT; корректная цена |

BL-01 имеет отдельные expired/exhausted/repeated-apply subcases; BL-03 — cart race и shared-coupon race разных пользователей. Каждый получает собственный результат. Injection включает один реальный изолированный SQL-кейс; NoSQL/command-подобные строки проверяются как отрицательный ввод, без отдельных runtime-движков.

CS-01: 200 сгенерированных попыток образуют один detection episode; три аккаунта с корректными парами — три case достижения доступа. Неверные пары создают контекст кампании, но не добавляют 197 заведомых неуспехов в знаменатель ASR. RP-01 содержит отдельно malicious попытку повторного эффекта и benign retry. Их намерение может быть неразличимо по HTTP; это ограничение отражается в метриках, а не устраняется передачей label detector.

Секретность id не заменяет owner-check; ошибка 500 не доказывает injection; JWT остаётся многократным. Украденный bearer позволяет новые допустимые операции. Rate policy не обещает остановить первую корректную пару, а правила detector покрывают только описанные признаки. Эти остаточные риски входят в отчёт.

Подготовка сценариев использует сокращённый API: login, GET профиля по ID, поиск products, GET cart, PUT /cart/items/{productId}, PUT /cart/coupon, POST /orders и GET заказа по ID. PUT количества создаёт или заменяет позицию; нулевое количество — отрицательный BL-02, не удаление. BO-01/BO-02 получают известные ID из приватного manifest; CS подтверждает доступ собственным профилем по ID. Для независимых случаев с пустой корзиной/без купона выделяются fixture-пользователи; удаления не нужны, отдельная ручка регистрации проверяется контрактными тестами и не меняет исходный fixture manifest. Повторный выбор купона проверяет BL-01; чтение cart после выбора не должно показывать накопление скидки. BL-03 использует разные key одной cartRevision либо общий купон разных пользователей.

## Модель эксперимента

| Сущность | Определение |
|---|---|
| Definition | Неизменяемая версия scenarios, fixture/seed, policy/rules snapshots и evaluation plan |
| Experiment | Запрос оператора выполнить definition; содержит runs |
| Run | Один target с чистой фикстурой, epoch, opaque detector partition и исполнением плана |
| Case / subcase | Одна цель атакующего и проверяемые инварианты |
| Episode | Заранее ограниченная последовательность; единица recall и benign FPR |
| Step | Действие плана, возможно с несколькими retries/HTTP-attempts |
| Attempt | Одна отправка с уникальным UUID attemptId=requestId доверенного harness |

Scenario definition задаёт id/version/family/subcase, actors, fixture requirements, последовательность и concurrent groups, input generator/seed, budgets, episode boundaries, attack-specific steps, benign/malicious labels и oracle ids. Control хранит полную definition; runner получает исполнимый план/синтетические credentials, detector — только наблюдения. Подготовительные login/cart steps не увеличивают число malicious cases.

До запуска сохраняется manifest с definition/plan/fixture hashes, кодом/контрактами/dependency/image versions, seed/businessTimeBase, actor mapping, policy/ruleset hashes, budgets, deadlines и evaluator version. UUID не кодируют сценарий или target; связь run↔detector partition известна только control. Полные snapshots хранятся вместе с hashes, чтобы конфигурацию можно было восстановить.

Пример создания:

```json
{
  "name": "Объектная авторизация",
  "scenarioRefs": [{"id": "BO-01", "version": 1}, {"id": "BO-02", "version": 1}],
  "targets": ["VULNERABLE", "PROTECTED"],
  "seed": 42,
  "fixtureVersion": "shop-demo-v1",
  "policyProfile": "baseline-vs-enforce-v1",
  "rulesetVersion": "rules-v1",
  "execution": {"parallelism": 1, "repetitions": 1}
}
```

Targets — только enum. Name 1…120 символов, scenarioRefs непустой список известных версий, repetitions/parallelism положительные в разрешённом бюджете. Неизвестные поля/версии и превышение бюджета — 400. BaseUrl, внешний callback, SQL assertions и executable expressions не принимаются. Objects/actors разрешаются по fixture manifest; labels и ожидаемые эффекты не выводятся из ответа detector.

## Встроенный runner и контракты управления

Runner — пакет `lab.control.runner` внутри исходников lab-control, без отдельного Maven-модуля. PlanExecutor/StepExecutor используют отдельный executor, Java HttpClient с фиксированными gateway targets, semaphore и конечные бюджеты. Один активный run в MVP. Cancellation token/deadline проверяются перед каждой новой попыткой/retry и ожиданием barrier. Сценарии поставляются доверенным кодом; пользовательский Java/shell/SQL не исполняется.

Orchestrator сохраняет один control.scenario_jobs на run: id/runId/epoch, plan/hash, deadline_at, state, final step_manifest/hash/sealed_at. `prepare(plan)` валидирует planHash и лимиты; `start(prepared, token, attemptSink)` запускает executor и возвращает completion handle; `cancel()` прекращает новые действия. Потеря UI-вкладки не прекращает серверное управление run. Final manifest записывается после всех результатов; прерванный процесс не возобновляет job.

| Интерфейс | Контракт |
|---|---|
| Operator: `POST /control/v1/experiments` | Создание definition, 201 `{experimentId, definitionHash, state:"CREATED"}` |
| Operator: `POST /control/v1/experiments/{id}/start` | 202 `{experimentId, runIds}` после принятия; конфликт активного run/state — 409 |
| Operator: `GET /control/v1/runs/{runId}` | State/revision, target, progress, cleanupSafe, failure reason, nullable reportId |
| Operator: `POST /control/v1/runs/{runId}/cancel` | 202 с commandId; окончание определяется состоянием run |
| Operator: `GET /control/v1/runs/{runId}/report` | JSON report; до готовности 409 REPORT_NOT_READY |
| Orchestrator → runner: Java | prepare/start/cancel; immutable PreparedPlan, cancellation token, completion handle |
| Runner → AttemptSink: Java | Bounded batches безопасных AttemptResult; подтверждение сохранения перед final manifest |
| Control → gateway/shop: `POST /internal/v1/runs/{runId}/commands` | PREPARE/OPEN/CLOSE/RESET с epoch; RESET после cleanupSafe |
| Control → detector: SQL jobs | PREPARE(partition, rulesetHash), CLOSE(partition, event IDs/hash), CANCEL; без labels |

Между runner и control нет HTTP endpoints, worker listener, heartbeat или lease. Внешние управляющие команды имеют schemaVersion=1, commandId, epoch, expected state/revision. Повтор commandId с тем же payload возвращает прежний результат, с другим — 409. HTTP 202 означает принятие, не завершение. Старый epoch не открывает admission нового run. Operator/admin credentials разделены; run credential в ScenarioHttpClient ограничен экспериментальным трафиком gateway.

AttemptResult v1: jobId/runId/stepId/attemptId, sequence, requestId=attemptId, traceId, method/route, безопасные requestSummary/responseEvidence, transportOutcome RESPONSE/TIMEOUT/CONNECT_ERROR/CANCELLED, nullable httpStatus, startedAt/finishedAt, durationMs и retryNumber. UUID существует до отправки; gateway проверяет его доверенный run context и передаёт shop. Retry — новый UUID. Это связывает logs/results при потере ответа; CONNECT_ERROR без gateway start сохраняет тот же UUID. Duplicate attemptId с другим payload — ошибка. Decisions присоединяются из audit, не угадываются по HTTP.

AttemptSink — порт, реализованный persistence adapter control. Запись ограниченными batches не требует сетевой доставки/ACK; ошибка или переполнение запрещают новые шаги и дают INCONCLUSIVE при неполноте. Сценарные классы не получают datasource/admin/evaluator/log-reader interfaces. Это граница модулей доверенного Java-кода, не sandbox: процесс control имеет соответствующие сети и credentials. Проверки клиента запрещают любой origin кроме gateway и служебные routes; запрет внешнего egress проверяется отдельно на контейнере. Fixtures устанавливает orchestration, не сценарный HTTP-клиент.

## Жизненный цикл и завершение

Нормальный путь: CREATED → PREPARING → RUNNING → DRAINING → EVALUATING → COMPLETED. Переходы сохраняются с revision/CAS; после restart control помечает незавершённый run INCONCLUSIVE и не возобновляет jobs. Терминальные альтернативы:

- FAILED — конфигурация/подготовка или исполнение технически не выполнены.
- CANCELLED — отмена после остановки новых действий и фиксации оставшихся.
- INCONCLUSIVE — исход или полнота доказательств не установлены.

Отрицательный результат защиты может быть достоверным COMPLETED. Любой выход из RUNNING, включая fatal/cancel, проходит DRAINING; желаемый terminal state хранится отдельно. При cleanupSafe=false даже терминальный run блокирует следующий reset на target.

PREPARING закрывает admission, проверяет отсутствие старых работ, устанавливает фикстуру и проверяет baseline invariants. Control регистрирует два JSONL stream (gateway и выбранный shop), проверяет writer/readonly mounts, фиксирует snapshots/namespace limiter, получает detector READY и результат локального prepare(planHash); затем открывает admission. Ready означает готовность работать, а не только живой процесс.

Сравнение состоит из последовательных paired runs с одинаковыми fixture/seed/business-time base, workload, concurrency и ruleset. Target DB и policy state раздельны. Порядок vulnerable/protected чередуется в повторениях; concurrency внутри race сохраняется. Для оценки вклада одной политики меняется только она при фиксированном backend. Seed не фиксирует OS/SQL interleaving: гонки запускаются серией с barrier и публикуемым числом повторов.

Барьер DRAINING:

1. Orchestrator отменяет новые шаги встроенного runner, ждёт completion handle и сохранения attempts/step manifest.
2. Gateway/shop закрывают admission epoch, включая задержанные requests.
3. Живые gateway/shop с неизменным sourceInstanceId дожидаются in-flight=0 и записывают STREAM_END(lastSeq). Client timeout не означает завершения SQL.
4. Log-ingestor в control дочитывает оба stream до END, проверяет seq, started/completed и связь с attempts. При полноте ставит DRAINED и формирует event IDs/hash для CLOSE.
5. Detector получает CLOSE, обрабатывает полный набор, закрывает окна и возвращает ACK с hashes/counts/errors.
6. Control снимает readonly конечное состояние, выполняет oracle и сохраняет отчёт.

Каждый этап имеет deadline из manifest. Недостающий event/result/terminal/STREAM_END, пропуск seq, недоступный snapshot или неподтверждённый CLOSE дают INCONCLUSIVE. Установка таймера «подождать несколько секунд» не заменяет барьер. Перезапуск любого участника или отказ обязательной инфраструктуры нарушает допущение опыта: INCONCLUSIVE, без продолжения. Старые исполнители останавливаются; новый run начинается после перезапуска стенда и подготовки фикстур.

## Независимый эталон

Приватный evaluation plan до запуска задаёт actors/owners, атакующие steps, цели, benign labels, инварианты и causal attribution. Oracle использует response evidence, before/after state и trusted fixture snapshots; он не вызывает production calculator/rule engine и не принимает BLOCK/incident как доказательство.

Case effect: EFFECT_OBSERVED / EFFECT_NOT_OBSERVED / ERROR / INCONCLUSIVE. Инварианты отдельно: HELD / VIOLATED / NOT_APPLICABLE. Prevention verdict содержит механизм и доказательство, что именно защита остановила эффект. CS-02 может достичь цели при корректной проверке JWT — это остаточный риск, не ошибка signature validation.

HTTP 200 при retry может быть сохранённым прежним Order; 409 при ошибочной транзакции может сопровождаться побочным эффектом. Неверные credentials и infrastructure rejects не объявляются успешной защитой. Для BOLA проверяется раскрытие полей, для login — пригодность token, для replay/race — число эффектов, для injection — canary/выборка, для бизнес-логики — соответствующий state invariant.

## Метрики

| Метрика | Единица и формула |
|---|---|
| ASR | Cases с EFFECT_OBSERVED / все полноценно оценённые malicious cases; по family/subcase и общий итог с явными весами |
| Detection recall | Malicious episodes с подходящим FINAL incident / все полностью наблюдённые malicious episodes |
| Incident precision | TP incidents / (TP + FP incidents) |
| Benign FPR | Benign episodes хотя бы с одним ложным incident / все полностью наблюдённые benign episodes |
| False block rate | Легитимные logical operations, ошибочно остановленные защитой / все пригодные легитимные operations |
| Detection delay | Время появления incident − первое attack-specific evidence time; live/final отдельно |
| Нагрузка | p50/p95/p99 latency, throughput, error rate, CPU/RAM при одинаковом workload |
| Coverage | Planned/executed/evaluable cases/episodes, missing steps/events, ERROR/INCONCLUSIVE и причины |

Recall включает предотвращённые попытки. `1−ASR` не называется общей эффективностью защиты: эффект мог отсутствовать по иной причине. Нулевой знаменатель — N/A. ERROR/INCONCLUSIVE исключаются из основной доли, но их counts/причины стоят рядом. Неполный run имеет диагностические частичные результаты; итоговое paired comparison требует двух COMPLETED runs.

Incident относится к episode по evidence event → request → attack-specific step и совместимому классу угрозы. Совпадение только run/source или подготовительного login недостаточно. Несколько incidents дают один detected episode, но несколько наблюдений для precision. Один incident покрывает несколько episodes только при evidence для каждого и считается один раз в precision. Неверный класс — FP; угроза без подходящего incident — FN. UNASSESSED применяется только при объективной нехватке/неоднозначности evidence, с видимым coverage.

Для гонок и стохастических профилей публикуются seeds/repetitions и k/n. Доверительные интервалы требуют указанного метода и единицы независимости; requests одного episode не являются независимыми наблюдениями. Числовые цели качества определяются после пилота и фиксируются до итоговой серии.

## Отчёт

Report v1 содержит reportId/revision, experiment/run ids, state/cleanupSafe, timestamps, snapshots/hashes/versions, target, seed/businessTimeBase, case/subcase/episode results, incidents/assessments, policy decisions, evidence references, метрики с числителями/знаменателями, coverage и остаточные риски. Paired report содержит оба run и условия сопоставимости.

После финализации отчёт неизменяем; повторный evaluator создаёт новую revision с причиной. Operator notes хранятся отдельно. JSON-export не содержит паролей, токенов и необработанных секретных запросов. Отсутствующий incident показывается как результат episode, а не созданное задним числом обнаружение.

## Сценарии
### 1. Credential Stuffing (CS-01: Перебор учетных данных)
**Задача оператора:** Проверить, способна ли защита остановить серию перебора пар логин - пароль и как система обрабатывает ситуацию, когда атака все-таки находит легитимный вход.

**Архитектура эпизода (Episode & Cases):**
Формируется 1 `detection episode`, состоящий из 200 HTTP-попыток. Внутри этого эпизода скрыты 3 реальных аккаунта с корректными парами. Остальные 197 неверных пар создают контекст кампании для детектора, но их заведомые неуспехи не раздувают знаменатель метрики ASR (Attack Success Rate).

**Шаги генератора (Scenario-runner):**
1. Получить из `fixture_manifest` 3 валидные пары и сгенерировать 197 неверных, используя зафиксированный `seed`.
2. Используя выделенный `executor` внутри `lab-control`, отправить 200 запросов `POST /auth/login`. Каждой отправке назначается уникальный `attemptId=requestId`.
3. Шаги подтверждения: для тех пар, которые вернули HTTP 200, извлечь токен и выполнить целевой `GET /users/{userId}`.
4. Отправить результаты без чувствительной информации батчами в `AttemptSink`.

**Критерии Evaluator:**
*   **EFFECT_OBSERVED:** Оценщик подтверждает успех атаки только в том случае, если полученный токен *действительно открывает профиль жертвы*.
*   Остаточный риск: Стенд явно декларирует, что первая корректная пара может не заблокироваться (это допустимый риск парольной аутентификации); это не считается ошибкой валидации подписи, но формирует `EFFECT_OBSERVED` для данного case.

### 2. BOLA / IDOR (BO-01: Подмена идентификатора заказа)
**Задача оператора:** Проверить строгую объектную авторизацию при запросах к API. Убедиться, что секретность ID не заменяет проверку владельца, и что украденный токен не дает прав на все объекты базы.

**Шаги генератора (Scenario-runner):**
1. В `PREPARING` стадии загружается `fixture_manifest`, содержащий синтетических акторов и их заказы.
2. Генератор получает `accessToken` для легитимного Actor A (выполняя подготовительный login).
3. Из приватного манифеста генератор извлекает известный `orderId` заказа, принадлежащего Actor B. (Генератору не нужно парсить базу данных или перебирать ID).
4. Отправляется `GET /api/v1/orders/{orderId}` с токеном A и `attemptId`. Результат фиксируется в `AttemptSink`.

**Критерии evaluator:**
*   **EFFECT_OBSERVED:** Оценщик берет `response evidence` (разрешенные поля HTTP-ответа, записанные control) и сверяет с приватным эталоном. Если поля чужого заказа раскрыты — атака успешна.
*   В защищенном режиме шлюз и БД проверяют `authenticated user`. Запрос чужого (или отсутствующего) заказа вернет строгий 404 NOT_FOUND. В этом случае Evaluator фиксирует `EFFECT_NOT_OBSERVED`.

### 3. Replay Attack (RP-01: Повтор операции Checkout)
**Задача оператора:** Проверить кэширование и транзакционную идемпотентность `POST /orders`. Система должна различать технический `retry` (повторная отправка того же `Idempotency-Key` с тем же телом после потери ответа сети) и вредоносную попытку создать повторный эффект (создать два заказа).

**Шаги генератора (Scenario-runner):**
1. Выполнить `GET /cart` и получить валидную `cartRevision`.
2. Отправить легитимный `POST /orders` с новым `Idempotency-Key` и дождаться успешного ответа (201).
3. Отправить *вредоносный повтор*: отправить абсолютно идентичный запрос (тот же ключ, та же ревизия), сгенерировав новый `attemptId` для логов шлюза. Записать outcome в `AttemptSink`.

**Критерии Evaluator:**
*   **EFFECT_OBSERVED:** Оценщик выполняет SQL-запрос `check_business_invariants` к `readonly` копии бизнес-состояния. Если инвариант `INV-CART-REVISION-ONCE` (уникальность order на пару cart/revision) возвращает **VIOLATED** (найдено более одного заказа), атака успешна.
*   В Protected-режиме система найдет закэшированный ответ и вернет его с заголовком `Idempotency-Replayed: true` (статус 201), но *без создания нового эффекта* (stateChanged=false в security-логе). Инвариант будет **HELD**, эффект — `EFFECT_NOT_OBSERVED`.

### 4. SQL Injection (IJ-01: Утечка через поиск каталога)
**Задача оператора:** Убедиться, что защита использует строгую SQL-параметризацию (или allowlist) и не позволяет специальным символам (`'`, `%`, `_`) в параметре поиска изменить AST запроса и обойти фильтр `published=true`.

**Шаги генератора (Scenario-runner):**
1. Сценарий генерирует payload, содержащий литеральную пунктуацию и SQL-подобные строки.
2. Отправляет запрос `GET /products?q={payload}`.

**Критерии Evaluator:**
*   **EFFECT_OBSERVED:** Оценщик парсит массив ответа (`Product[]`). Если в нормативную выдачу попал скрытый контрольный товар (`draft canary`, известный из `fixture_manifest`), это 100% подтверждение утечки.
*   *Важное архитектурное уточнение:* Получение ошибки HTTP 500 (Internal Error) из-за сломанного SQL-запроса в Vulnerable режиме **не доказывает** саму инъекцию (эффекта раскрытия нет). Защита в Protected режиме должна использовать параметризованный SQL, корректно возвращая обычный поиск или пустой массив.

### 5. Business Logic Abuse (BL-01: Использование исчерпанного купона)
**Задача оператора:** Проверить атомарность бизнес-транзакции и защиту инварианта `INV-COUPON-LIMIT` на этапе фиксации заказа (commit boundary). Сервер обязан проверять остаток купона не только при его выборе в корзине, но и в момент финального оформления.

**Шаги генератора (Scenario-runner):**
1. Взять из `fixture_manifest` купон, который уже исчерпал свой лимит (exhausted) или срок действия (expired).
2. Отправить `PUT /cart/coupon`, выбрав данный купон. (Сам по себе выбор не резервирует лимит, это допустимая техническая операция).
3. Отправить `POST /orders`, вызывая операцию Checkout, которая должна атомарно удержать лимит.

**Критерии Evaluator:**
*   **EFFECT_OBSERVED:** Оценщик берет baseline счетчики из манифеста (`used_count` и `max_uses`) и сверяет их с текущим readonly-состоянием таблиц. Если инвариант `INV-COUPON-LIMIT` **VIOLATED** (создан заказ с незаконной скидкой, а лимит превышен), бизнес-логика сломана.
*   В Protected-режиме сервер берет блокировку (`lock`), видит исчерпание/истечение срока по бизнес-часам (строго `now < expires_at`), откатывает транзакцию (rollback) и возвращает 409 CONFLICT. Инвариант **HELD**, эффект — `EFFECT_NOT_OBSERVED`.

---
**Вывод для всех сценариев:**
Генератор атак отправляет HTTP-запросы с назначенными `attemptId` и фиксирует их транспортный исход. Абсолютно все выводы об `ASR` (Attack Success Rate), нарушенных инвариантах и корректности работы детектора принимает **Evaluator**, дождавшись барьера `DRAINING` (когда все `in-flight` транзакции равны 0 и потоки логов `STREAM_END` закрыты). Любая потеря лога или доказательства на этом пути приведет к статусу `INCONCLUSIVE` (не доказано), а не к "успешной защите".
