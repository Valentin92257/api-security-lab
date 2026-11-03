# Сценарии, эксперименты и оценка

## Угрозы и гарантии

Стенд моделирует клиента локального API с синтетическими credentials и объектами. Активы: учётные записи/JWT, профили/заказы, каталог/цены, корзина/купоны, доказательные access-логи и независимый ground truth. Runtime-компрометация Docker host/DBA не входит в модель. Генератор работает по конечному каталогу сценариев и встроенным targets.

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

BL-01 имеет отдельные expired/exhausted/repeated-apply subcases; BL-03 - cart race и shared-coupon race разных пользователей. Каждый получает собственный результат. Injection включает один реальный изолированный SQL-кейс; NoSQL/command-подобные строки проверяются как отрицательный ввод, без отдельных runtime-движков.

CS-01: 200 сгенерированных попыток образуют один detection episode; три аккаунта с корректными парами - три case достижения доступа. Неверные пары создают контекст кампании, но не добавляют 197 заведомых неуспехов в знаменатель ASR. RP-01 содержит отдельно malicious попытку повторного эффекта и benign retry. Их намерение может быть неразличимо по HTTP; это ограничение отражается в метриках, а не устраняется передачей label detector.

Секретность id не заменяет owner-check; ошибка 500 не доказывает injection; JWT остаётся многократным. Украденный bearer позволяет новые допустимые операции. Rate policy не обещает остановить первую корректную пару, а правила detector покрывают только описанные признаки. Эти остаточные риски входят в отчёт.

Подготовка сценариев использует сокращённый API: login, GET профиля по ID, поиск products, GET cart, PUT /cart/items/{productId}, PUT /cart/coupon, POST /orders и GET заказа по ID. PUT количества создаёт или заменяет позицию; нулевое количество - отрицательный BL-02, не удаление. BO-01/BO-02 получают известные ID из приватного manifest; CS подтверждает доступ собственным профилем по ID. Для независимых случаев с пустой корзиной/без купона выделяются fixture-пользователи; удаления не нужны, отдельная ручка регистрации проверяется контрактными тестами и не меняет исходный fixture manifest. Повторный выбор купона проверяет BL-01; чтение cart после выбора не должно показывать накопление скидки. BL-03 использует разные key одной cartRevision либо общий купон разных пользователей.

## Модель эксперимента

| Сущность | Определение |
|---|---|
| Trial | Испытание выбранных families с seed; включает vulnerable/protected runs |
| Run | Один режим с чистой фикстурой, runId и исполнением плана |
| Case / subcase | Одна цель атакующего и проверяемые инварианты |
| Episode | Заранее ограниченная последовательность; единица recall и benign FPR |
| Step | Действие плана, возможно с несколькими retries/HTTP-attempts |
| Attempt | Одна отправка с уникальным UUID attemptId=requestId доверенного harness |

Scenario definition задаёт id/version/family/subcase, actors, fixture requirements, последовательность и concurrent groups, input generator/seed, budgets, episode boundaries, attack-specific steps, benign/malicious labels и oracle ids. Control хранит полную definition; runner получает исполнимый план/синтетические credentials, detector - только наблюдения. Подготовительные login/cart steps не увеличивают число malicious cases.

До запуска сохраняется manifest с definition/plan/fixture hashes, кодом/контрактами/dependency/image versions, seed/businessTimeBase, actor mapping, policy/ruleset hashes, budgets, deadlines и evaluator version. UUID не кодируют сценарий или target; связь runId с режимом известна Control, Detector получает только runId. Это внутренние данные Control, не дополнительные поля HTTP TrialReport. Полные исходные данные сохраняются приватно для независимой проверки.

Пример создания через POST /control/v1/trials с Idempotency-Key:

```json
{
  "families": ["BOLA", "REPLAY"],
  "seed": 42
}
```

Выбирается от одного до пяти известных families или все пять; seed необязателен. Оба режима запускаются автоматически, сначала vulnerable, затем protected. Остальные настройки фиксируются конфигурацией стенда. Неизвестные поля и превышение бюджета - 400. BaseUrl, внешний callback, SQL assertions и executable expressions не принимаются. Objects/actors разрешаются по подготовленным исходным данным; labels и ожидаемые эффекты не выводятся из ответа Detector.

## Встроенный runner и контракты управления

Runner - пакет `lab.control.runner` внутри lab-control. PlanExecutor/StepExecutor используют отдельный executor, Java HttpClient с фиксированными gateway targets, semaphore и конечные бюджеты. Один активный trial с двумя последовательными runs в MVP. Cancellation token/deadline проверяются перед каждой новой попыткой/retry и ожиданием barrier. Сценарии поставляются доверенным кодом; пользовательский Java/shell/SQL не исполняется.

Control подготавливает и запускает сценарии внутри процесса; завершение подтверждается после сохранения результатов всех начатых запросов. Отмена прекращает новые действия. Потеря UI-вкладки не прекращает серверное управление run. Прерванный процесс не возобновляет выполнение. Внутренние Java-интерфейсы определяются при реализации.

| Интерфейс | Контракт |
|---|---|
| Operator: `GET /control/v1/scenarios` | Пять доступных families |
| Operator: `POST /control/v1/trials` | Создать и сразу запустить оба режима; 202 новый trial, 200 идентичный повтор |
| Operator: `GET /control/v1/trials` | Список испытаний с пагинацией |
| Operator: `GET /control/v1/trials/{trialId}` | Состояние, общий прогресс, cleanupSafe и необязательный error |
| Operator: `POST /control/v1/trials/{trialId}/cancel` | 202 Trial; окончание определяется состоянием |
| Operator: `GET /control/v1/trials/{trialId}/report` | TrialReport; до готовности 409 |
| Control → Gateway/Shop: Runtime API | PUT /runs/{runId}, GET status, POST open/close; без HTTP подготовки/reset БД |
| Control → Detector: HTTP API | prepare/status/finish/incidents; Shop/Gateway отправляют log-batches |

Между Runner и Control нет HTTP endpoints, worker listener, heartbeat или lease. POST trials требует Idempotency-Key: одинаковый ключ/body возвращает прежний trial, другой body - 409. Управляющие prepare/open/close/finish допускают безопасный идентичный повтор; изменённые контекст или границы дают 409. HTTP 202 означает принятие, не завершение. Operator/service credentials отделены от пользовательского JWT; runId берётся из Runtime-контекста, без X-Lab-заголовков.

Control сохраняет результат каждой отправки и её связь с проверкой. UUID создаётся до отправки, передаётся в X-Request-Id и сохраняется Gateway/Shop. Retry получает новый UUID. Пароли и JWT в сохраняемые результаты не попадают; потеря результата даёт INCONCLUSIVE.

Результаты запросов сохраняет Control. Ошибка сохранения или переполнение запрещают новые шаги и дают INCONCLUSIVE при неполноте. Сценарные классы не получают datasource/admin/evaluator interfaces. Это граница доверенного Java-кода, не sandbox: процесс Control имеет соответствующие сети и credentials. HTTP-клиент генератора запрещает любой origin кроме Gateway и служебные routes; запрет внешнего egress проверяется отдельно на контейнере. Fixtures устанавливает отдельный модуль Control напрямую в БД, не сценарный HTTP-клиент.

## Жизненный цикл и завершение

Нормальный путь: PREPARING → RUNNING → FINALIZING → COMPLETED. Trial хранит общий прогресс и cleanupSafe; после restart control помечает незавершённый run INCONCLUSIVE и не возобновляет исполнение. Терминальные альтернативы:

- FAILED - конфигурация/подготовка или исполнение технически не выполнены.
- CANCELLED - отмена после остановки новых действий и фиксации оставшихся.
- INCONCLUSIVE - исход или полнота доказательств не установлены.

Отрицательный результат защиты может быть достоверным COMPLETED. Любой выход из RUNNING, включая fatal/cancel, проходит FINALIZING; желаемый terminal state хранится отдельно. При cleanupSafe=false даже терминальный run блокирует следующий reset на target.

PREPARING закрывает admission, проверяет отсутствие старых работ, устанавливает фикстуру и проверяет baseline invariants. Control подготавливает Runtime-контексты Shop/Gateway, Runner и анализ Detector, проверяет instanceId и чистое состояние limiter protected Shop; затем открывает Shop, после него Gateway. Ready означает готовность работать, а не только живой процесс.

Сравнение состоит из последовательных paired runs с одинаковыми fixture/seed/business-time base, workload, concurrency и ruleset. Target DB и policy state раздельны. Порядок фиксирован: vulnerable, затем protected; concurrency внутри race сохраняется. Текущий API предоставляет два фиксированных набора защит, без переключателей отдельных политик. Seed не фиксирует OS/SQL interleaving: гонки запускаются серией с barrier и публикуемым числом повторов.

Барьер FINALIZING:

1. Orchestrator отменяет новые шаги/retries, ждёт completion и сохранения attempts.
2. Закрывает Gateway и ждёт CLOSED, затем закрывает Shop и ждёт CLOSED.
3. CLOSED означает in-flight=0 и окончательный lastSequence при неизменном instanceId. Client timeout не означает завершения SQL.
4. Control передаёт gatewayLastSequence, shopLastSequence и deadlineAt в finishAnalysis; Detector проверяет непрерывные 1..N и обрабатывает все логи.
5. После COMPLETED Control сверяет observedRequestIds с attempts и получает все итоговые incidents.
6. Control снимает readonly конечное состояние, выполняет oracle и сохраняет отчёт; после этого разрешается безопасная подготовка следующей БД.

Каждый этап имеет deadline. Недостающий лог/result, пропуск sequence или неподтверждённый COMPLETED дают INCONCLUSIVE. Фиксированное ожидание «несколько секунд» не заменяет барьер. В FINISHING принимаются только записи до объявленных N, после COMPLETED/FAILED - только идентичные сохранённые повторы; изменённый finish или N ниже сохранённого sequence даёт 409. Перезапуск любого участника или отказ обязательной инфраструктуры нарушает допущение опыта: INCONCLUSIVE, без продолжения. Старые исполнители останавливаются; новый run начинается после подготовки фикстур.

## Независимый эталон

Приватный evaluation plan до запуска задаёт actors/owners, атакующие steps, цели, benign labels, инварианты и causal attribution. Oracle использует response evidence, before/after state и trusted fixture snapshots; он не вызывает production calculator/rule engine и не принимает BLOCK/incident как доказательство.

Case effect: EFFECT_OBSERVED / EFFECT_NOT_OBSERVED / INCONCLUSIVE. Проверки инвариантов служат доказательством effect и описываются в reason; технические ошибки отражаются в state/error. Обнаружение оценивается отдельно в detection. CS-02 может достичь цели при корректной проверке JWT - это остаточный риск, не ошибка signature validation.

HTTP 200 при retry может быть сохранённым прежним Order; 409 при ошибочной транзакции может сопровождаться побочным эффектом. Неверные credentials и infrastructure rejects не объявляются успешной защитой. Для BOLA проверяется раскрытие полей, для login - пригодность token, для replay/race - число эффектов, для injection - canary/выборка, для бизнес-логики - соответствующий state invariant.

## Метрики

| Метрика | Единица и формула |
|---|---|
| ASR | Cases с EFFECT_OBSERVED / все полноценно оценённые malicious cases; по family/subcase и общий итог с явными весами |
| Detection recall | Malicious episodes с detection=DETECTED / все полностью наблюдённые malicious episodes |
| Incident precision | TP incidents / (TP + FP incidents) |
| Benign FPR | Benign episodes хотя бы с одним ложным incident / все полностью наблюдённые benign episodes |
| False block rate | Легитимные logical operations, ошибочно остановленные защитой / все пригодные легитимные operations |
| Detection delay | Время появления incident − первое attack-specific evidence time; для итоговых incidents, отдельным измерением |
| Нагрузка | p50/p95/p99 latency, throughput, error rate, CPU/RAM при одинаковом workload |
| Coverage | Planned/executed/evaluable cases/episodes, missing steps/logs, ошибки/INCONCLUSIVE и причины |

Recall включает предотвращённые попытки. `1−ASR` не называется общей эффективностью защиты: эффект мог отсутствовать по иной причине. Нулевой знаменатель - N/A. INCONCLUSIVE/UNASSESSED исключаются из соответствующих знаменателей, но их counts/причины стоят рядом. Неполный run имеет диагностические частичные результаты; итоговое paired comparison требует двух COMPLETED runs.

Incident относится к episode по requestId → attack-specific step. Совпадения только run/IP или подготовительного login недостаточно. Несколько incidents дают один detected episode, но несколько наблюдений precision. Один incident покрывает несколько episodes только при evidence для каждого и считается один раз в precision. Ошибка/отсутствие family не меняет TP; её объясняют в reason. Неопределённая оценка - UNASSESSED. detection выводится по [правилам контракта](../contracts/semantics.md), одинаково у cases одного episode и отдельно от effect. MISSED/CLEAN требуют полноты шагов и логов; при незавершённом анализе detection=INCONCLUSIVE и incidents=null.

False block rate, detection delay и нагрузка требуют отдельной технической телеметрии: текущий TrialReport не хранит их исходные данные.

Для гонок и стохастических профилей публикуются seeds/repetitions и k/n. Доверительные интервалы требуют указанного метода и единицы независимости; requests одного episode не являются независимыми наблюдениями. Числовые цели качества определяются после пилота и фиксируются до итоговой серии.

## Отчёт

TrialReport содержит trialId, families, seed, terminal state и до двух RunReport. Каждый run содержит runId, mode, state, cases, incidents и необязательный error. CaseResult задаёт caseId/episodeId, family, label ATTACK/BENIGN, effect, detection, requestIds и reason. IncidentAssessment включает итоговый incident, verdict, matchedEpisodeIds и reason. Метрики UI выводит из cases/incidents, отдельного массива episodes и дублирующих счётчиков нет.

После финализации отчёт неизменяем; пересчёт и operator notes не входят в текущий HTTP-контракт. JSON-отчёт можно сохранить на стороне UI; он не содержит паролей, токенов и необработанных секретных запросов. Отсутствующий incident показывается как detection результата episode, а не созданное задним числом обнаружение.
