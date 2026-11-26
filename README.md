# API Security Lab

Java 25 / Spring Boot 4.0.8. Maven-модули: общая библиотека контрактов `libs/lab-contracts`
и четыре приложения `apps/shop-api`, `apps/lab-gateway`, `apps/lab-detector`, `apps/lab-control`.
Runner - пакет `lab.control.runner` внутри control.
Shop запускается в двух контейнерах с профилями `vulnerable` и `protected`.

## Credential stuffing

`ScenarioRunner` запускает сценарий в отдельном потоке внутри Control. Фикстуры магазина (пользователи,
каталог с draft canary, купоны, корзины, исторические заказы, baseline для Oracle и пары попыток входа)
детерминированно генерируются по `seed` в `lab.control.fixture` (`FixtureGenerator` / `ShopFixtureManager`)
и загружаются в выбранную БД (`vulnerable` или `protected`) под ролью `fixture_admin`:

```java
FixtureManifest manifest = fixtureManager.generate(42, Instant.now());
fixtureManager.load(ShopTarget.PROTECTED, manifest);
var attempts = manifest.credentialStuffing().attempts().stream()
        .map(p -> new CredentialStuffingScenario.LoginAttempt(p.userId(), p.login(), p.password()))
        .toList();
RunHandle handle = runner.start(trialId, runId,
        run -> scenario.run(run, GatewayTarget.PROTECTED, attempts));
CompletionStage<RunResult> completion = handle.completion();
```

По умолчанию один запуск делает 200 login: 3 с верными парами и 197 с неверными паролями
(размер выборки настраивается параметрами `lab.fixture.user-count`, `lab.fixture.cs-attempts`
и `lab.fixture.cs-valid-accounts`). Допускается до 200 попыток: бюджет учитывает login и чтение
профиля после каждого успешного входа, даже если все пароли приняты. Seed определяет пользователей,
каталог, заказы, пары и порядок попыток.
После каждого успешного login идёт GET своего профиля с полученным JWT. RequestId, время и JWT могут отличаться.

Runner проверяет login-запросы сценария и ответы Shop по существующим OpenAPI/JSON Schema.
Некорректный ответ сохраняется как `CONTRACT_VIOLATION` с HTTP-статусом, без тела и деталей ошибки.
Общей проверки схемы исходящих запросов нет: другим атакам нужны намеренно некорректные данные.
Ответ ограничен 2 MiB, получение HTTP-ответа - 5 секундами; редиректы запрещены.
В `control.step_results` сохраняются метаданные и разрешенные поля ответа на чтение заказа. Пароли и JWT используются в памяти.

Каждый запуск создает свое состояние сценария. Login и чтение профиля не меняют бизнес-данные.
Runtime API, очистка лимитов и подключение к `POST /trials` выполняются отдельно.

Runner принимает один активный прогон; повторный запуск до завершения дает `RejectedExecutionException`.
На прогон разрешено до 400 отправок, результаты хранятся в ограниченном списке метаданных.
Общий deadline задается `lab.runner.run-timeout`, по умолчанию `PT5M` (5 минут).
Он включает подготовку и сохранение шагов. Отмена и deadline запрещают новые отправки
и отменяют ожидаемый HTTP-запрос, сохраняя его исход как `INTERRUPTED` или `TIMEOUT`.
Запись результата в БД не прерывается; итог может прийти позже deadline, после окончания записи.

`handle.cancel()` только запрашивает остановку. `completion()` возвращает защищенный `CompletionStage`:
отмена его копии `CompletableFuture` не отменяет прогон и не подтверждает остановку.
`RunResult` содержит исход (`COMPLETED`, `CANCELLED`, `TIMED_OUT`, `FAILED`), число начатых шагов
и сохраненные метаданные. Ошибка сохранения дает `FAILED` и запрещает следующие отправки.
`COMPLETED` означает выполнение сценария, оценку успеха атаки делает Evaluator.
Сценарии передаются только доверенным Java-кодом Control, без пользовательского кода и HTTP endpoints.
Продолжение orchestration после `completion()` выполняется на собственном executor Control.
Отмена HTTP не подтверждает завершение SQL в Shop; это проверяется отдельно через Runtime.

## BOLA / IDOR

`BolaScenario` проверяет объектный доступ к заказам (BO-01, THR-3). Атакующий входит
и своим токеном читает известные заказы жертв. Контрольные запросы читают собственный
и отсутствующий заказ тем же токеном. План содержит учетные данные и ID заказов без эталонов.
Каждая отправка проходит через `StepExecutor` и сохраняется в `control.step_results` (EXP-2).
Для корректного ответа 200 на чтение заказа сохраняется `order_evidence`: id, userId,
cartId, cartRevision, subtotal, discount, total, currency и число позиций. Полные тела,
пароли и JWT не сохраняются.

```java
var bola = manifest.bola();
var plan = new BolaScenario.BolaPlan(
        bola.attacker().id(), bola.attacker().login(), bola.attacker().password(),
        bola.attackerOwnOrder().id(), bola.missingOrderId(),
        bola.victimOrders().stream().map(FixtureOrder::id).toList());
RunHandle handle = runner.start(trialId, runId,
        run -> scenario.run(run, GatewayTarget.PROTECTED, plan));
RunResult completed = handle.completion().toCompletableFuture().join();
List<BolaAssessment> assessments = evaluator.evaluate(
        completed.trialId(), completed.runId(), GatewayTarget.PROTECTED, bola);
```

`BolaEvaluator` читает сохраненные шаги выбранного trial/run/target и отдельно сравнивает
их с приватным эталоном. Результаты, ожидаемые поля и связь по requestId сохраняются
в `control.bola_assessments` одной транзакцией. Ошибка записи откатывает всю оценку.
Повторная оценка того же run не перезаписывает сохраненные результаты.

Совпадение контрольных полей чужого заказа дает `EFFECT_OBSERVED`, строгий 404 -
`EFFECT_NOT_OBSERVED`. Таймаут, нарушение контракта, отсутствие доказательства,
расхождение с эталоном или другой статус дают `INCONCLUSIVE`.
Для собственного заказа одного 200 недостаточно: поля также должны совпасть с его эталоном.
Блокировка собственного заказа и не-404 ответ на отсутствующий дают `EFFECT_OBSERVED`.
Подмена сегмента пути запрещена каталогом `ShopRoute`. Пароль скрыт в `toString` плана.
План допускает до 397 чужих заказов: еще три шага нужны для входа и контрольных запросов.

Подключение к `POST /trials`, Runtime API и ожидание барьера финализации остаются
отдельной задачей Control. Оценка вызывается после завершения шагов и финализации опыта.

## Запуск

Нужны Docker с Compose v2, `openssl` и `curl` (на Windows скрипт запускается из WSL или Git Bash).
Одна команда из корня репозитория:

```sh
./scripts/start.sh
```

При первом запуске скрипт создаёт `.env` из `.env.example` со случайными секретами, собирает образы,
запускает все сервисы и проверяет health Control. Первая сборка занимает несколько минут. Остановка: `docker compose down`.
Если volume с БД уже есть, а `.env` нет, скрипт останавливается: новые пароли не подойдут к созданным ролям.

То же вручную:

```sh
cp .env.example .env   # замени все replace-with-* на случайные секреты: openssl rand -hex 32
docker compose up -d --build --wait
curl --fail http://127.0.0.1:8080/health
```

### Переменные окружения

Настройки задаются в `.env` (образец - `.env.example`), файл `.env` не коммитится.

| Переменная                        | Обязательна | По умолчанию                 | Назначение                                                                |
|-----------------------------------|-------------|------------------------------|---------------------------------------------------------------------------|
| `LAB_OPERATOR_TOKEN`              | да          | -                            | Bearer-токен оператора Control                                            |
| `POSTGRES_PASSWORD`               | да          | -                            | Пароль администратора БД                                                  |
| `LAB_CONTROL_DB_PASSWORD`         | да          | -                            | Пароль рабочей роли `control_app`                                         |
| `LAB_FIXTURE_DB_PASSWORD`         | да          | -                            | Пароль роли `fixture_admin` для загрузки фикстур в БД Shop                |
| `LAB_FIXTURE_USER_COUNT`          | нет         | `24`                         | Число пользователей в фикстуре магазина (минимум 24)                      |
| `LAB_FIXTURE_CS_ATTEMPTS`         | нет         | `200`                        | Число попыток входа в Credential Stuffing: от числа валидных аккаунтов до 200 |
| `LAB_FIXTURE_CS_VALID_ACCOUNTS`   | нет         | `3`                          | Число валидных аккаунтов-жертв в сценарии Credential Stuffing             |
| `LAB_DETECTOR_CONTROL_TOKEN`      | да          | -                            | Управление и чтение Detector из Control                                   |
| `LAB_DETECTOR_GATEWAY_TOKEN`      | да          | -                            | Запись логов только producer GATEWAY                                      |
| `LAB_DETECTOR_SHOP_TOKEN`         | да          | -                            | Запись логов только producer SHOP                                         |
| `LAB_DETECTOR_DB_PASSWORD`        | да          | -                            | Пароль рабочей роли `detector_app`                                        |
| `LAB_DETECTOR_MIGRATION_PASSWORD` | да          | -                            | Пароль роли миграций `detector_migrator`                                  |
| `POSTGRES_USER`                   | нет         | `lab_admin`                  | Администратор БД; должен совпадать с администратором существующего volume |
| `LAB_CONTROL_PORT`                | нет         | `8080`                       | Порт Control на `127.0.0.1`                                               |
| `SHOP_PROTECTED_URL`              | нет         | `http://shop-protected:8080` | Адрес protected-магазина для Gateway                                      |
| `GATEWAY_CONNECT_TIMEOUT`         | нет         | `2s`                         | Таймаут соединения Gateway                                                |
| `GATEWAY_UPSTREAM_TIMEOUT`        | нет         | `5s`                         | Таймаут ответа магазина                                                   |
| `GATEWAY_MAX_BODY_BYTES`          | нет         | `8388608`                    | Максимальный размер тела запроса/ответа                                   |
| `JWT_SECRET_PROTECTED`            | да          | -                            | JWT секрет защищенной версии API: подпись в Shop и проверка в Gateway     |
| `JWT_SECRET_VULNERABLE`           | да          | -                            | JWT секрет для авторизации уязвимой версии API                            |
| `IDEMPOTENCY_SECRET`              | да          | -                            | HMAC-ключ для хеширования `Idempotency-Key` в checkout                    |

Пароли БД применяются только при создании volume; изменение `.env` позже их не меняет (см. [БД](#бд)).

Локальные Maven и JDK не нужны: сборка и модульные тесты выполняются в Docker.
Control опубликован на `127.0.0.1:8080` (`LAB_CONTROL_PORT`) через отдельную сеть `control-public`.
Остальные сервисы подключены только к internal-сетям и с хоста недоступны.
Health Control проверяет БД. Shop реализует все девять операций API (регистрацию, вход с JWT, профиль, каталог, корзину
и заказы) в обоих режимах; Gateway проверяет JWT и проксирует protected-запросы со сквозным ID и access-логами. Detector принимает
LogBatch, применяет правила входа CS-01-CS-05 и завершает анализ по границам последовательностей.
Java-контейнеры работают без root с read-only файловой системой и временными файлами в `/tmp`.
Состояние и логи: `docker compose ps`, `docker compose logs --tail=100`.

## БД

PostgreSQL 17 хранит `lab_control`, `lab_detector`, `vulnerable` и `protected` в volume `control-data`.
`docker compose down` сохраняет данные, `docker compose down --volumes` удаляет их.

PostgreSQL создаёт базы данных через скрипты в `/docker-entrypoint-initdb.d/` при первом запуске с пустым volume. При старте Control Flyway применяет миграции из `db/control/migrations`, включённые в JAR, с отдельными административными credentials. Рабочие запросы используют DML-роль `control_app`. Shop также применяет миграции при старте. Отдельного контейнера миграций нет.
`POSTGRES_USER` по умолчанию равен `lab_admin` и должен совпадать с администратором существующего volume.
Изменение `.env` не меняет созданные роли и пароли. Применённые миграции не редактируй; следующие добавляй как V3, V4 и далее.

На существующем volume init-скрипты повторно не выполняются. Если базы уже созданы, дополнительных действий не нужно. Для создания недостающих баз без удаления данных:

```sh
docker compose exec -T db sh -c 'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -f /docker-entrypoint-initdb.d/20-databases.sql'
```

Новые миграции таблиц применяются при следующем запуске соответствующего приложения.

Detector владеет схемой `detector` в `lab_detector`. Bootstrap
`db/detector/bootstrap.sql` создаёт собственные роли, Flyway применяет
`db/detector/migrations` от `detector_migrator`; запросы приложения работают от
`detector_app`, без DDL и без UPDATE/DELETE логов и incidents. Пароли администратора,
Control и Shop в Detector не передаются. Последующие изменения схемы добавляются
как V2, V3 и далее, применённые миграции не редактируются.

Для volume, созданного до появления Detector, добавь пять `LAB_DETECTOR_*` секретов
из `.env.example`, затем создай недостающую БД и роли без удаления старых данных:

```sh
docker compose up -d db
docker compose exec -T db sh -c 'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -f /docker-entrypoint-initdb.d/15-detector.sql'
docker compose up -d --build lab-detector
```

Повтор bootstrap сохраняет существующие роли/пароли и данные. Новая БД и миграции
создаются автоматически на пустом volume.

Для загрузки фикстур и запуска `check_business_invariants(jsonb)` из Control используется отдельная
роль `fixture_admin` (`db/shop/fixture-roles.sql`), у которой есть DML/`TRUNCATE`, обновление sequence
и `EXECUTE` на функцию инвариантов только в `vulnerable` и `protected` (без DDL и без доступа к
`lab_control` и `lab_detector`). Для существующего volume добавь `LAB_FIXTURE_DB_PASSWORD` из `.env.example`
и примени init-скрипт роли перед перезапуском Shop и Control:

```sh
docker compose up -d db
docker compose exec -T db sh -c 'psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -f /docker-entrypoint-initdb.d/25-shop-fixture-roles.sql'
docker compose up -d --build shop-vulnerable shop-protected lab-control
```

Миграция `V4__fixture_admin_grants.sql` выдаёт права на существующие объекты `vulnerable` и `protected` при старте Shop.
Init-скрипт роли и миграция Control `V6__restrict_database_access.sql` закрывают доступ `PUBLIC` к `lab_control`.
При загрузке фикстур счетчики ID только увеличиваются. Откат данных может оставить пропуски ID.

## Detector

[ADR-0002](docs/adr/0002-detector-ingestion-and-storage.md) описывает схему, транзакции
и завершение анализа. API доступен внутри сети `lab-ingest` по
`http://lab-detector:8080/internal/v1`; порт на хост не публикуется.

| Операция | Роль и результат |
|---|---|
| PUT `/runs/{runId}` | Control: 201 для нового run, 200 для повтора |
| GET `/runs/{runId}` | Control: состояние и instanceId; observedRequestIds только после COMPLETED |
| POST `/runs/{runId}/log-batches` | Gateway/Shop: только свой producer; 204 после сохранения всей пачки |
| PUT `/runs/{runId}/finish` | Control: фиксация обоих lastSequence и deadlineAt, 202 |
| GET `/runs/{runId}/incidents` | Control: текущие страницы в ACCEPTING/FINISHING, итоговые в COMPLETED; FAILED — 409 |
| GET `/events` | Control: access-события всех runs или одного runId, фильтры и курсорная пагинация |

`GET /internal/v1/events` принимает необязательные фильтры `runId`, `from`, `to`,
`producer`, `requestId`, `method`, `status`, `statusClass`, `route`, `remoteAddr`,
`userId`. Время задаётся с часовым поясом: `from` включительно, `to` исключительно;
точность до наносекунд сохраняется. Для дат с положительным смещением знак `+`
нужно URL-кодировать как `%2B`. Строковые фильтры сравниваются точно; разные фильтры
объединяются через AND, повторные `status=401&status=403` — через OR.
`statusClass` принимает `1xx`..`5xx` и несовместим с `status`.
Неизвестные, пустые и повторные скалярные параметры отклоняются с 400.

Например, `/internal/v1/events?producer=SHOP&statusClass=4xx&limit=20` возвращает
последние ошибки Shop среди всех runs. Ответ содержит `items` и `nextCursor`;
каждый элемент — `{runId, producer, log}`, где `log` соответствует существующему
AccessLog со всеми nullable-полями. `limit` по умолчанию 20, максимум 100.
Для продолжения нужно повторить фильтры и передать полученный `cursor`;
порядок/дубликаты status и эквивалентные часовые пояса не меняют фильтры.
Размер страницы можно менять. `nextCursor=null` означает конец доступных сейчас
результатов. Неверный курсор или изменение фильтров дают 400; явно заданный
несуществующий runId — 404, отсутствие совпадений — пустую страницу с 200.

События выдаются по убыванию `time, runId, producer, sequence`, с учётом наносекунд.
Чтение доступно в ACCEPTING, FINISHING, COMPLETED и FAILED. Курсор задаёт позицию,
а не общий снимок между страницами: новые или поздно доставленные записи могут
оказаться в уже просмотренном диапазоне. Для их просмотра нужно обновить список
без курсора. Данные терминальных runs неизменяемы; список всех runs может расти
при новых запусках. Ошибки чтения событий не меняют состояние анализа.

Три токена должны быть непустыми, разными и без пробелов. Producer не может управлять
run или читать результаты; токен Control не пишет логи. Ключ доставки —
`(runId, producer, sequence)`; повторы сравниваются по всем значениям DTO AccessLog
после штатной десериализации и не увеличивают счётчики. Разные числовые литералы,
дающие один double, считаются одинаковыми. Конфликт отклоняет пачку целиком с 409.

Служебные токены имеют область действия всего Detector: Control управляет всеми
runs, Gateway/Shop пишут своего producer в любой подготовленный run. Владельцев runs
и привязки токена к одному run в контракте нет. UUID не заменяет проверку роли;
чтение результатов и запись чужого producer запрещены независимо от знания runId.

Токены проверяются в `ServiceAccessFilter`: заголовок Authorization не копируется
в состояние run, access-логи или ответы. В текущем Compose используется HTTP без TLS;
internal-сеть ограничивает доступ, но не шифрует трафик. Секреты доступны в окружении
контейнера и памяти JVM, поэтому доступ к Docker, процессу и дампам памяти остаётся
доверенной административной границей.

Finish ждёт все номера 1..N отдельно у каждого producer, включая N=0. Поздние пачки
допустимы внутри объявленных границ. Изменённый повтор finish даёт 409; идентичный
возвращает текущее состояние. Ошибка или deadline переводят анализ в FAILED.
После COMPLETED/FAILED допустимы только сохранённые идентичные повторы логов.
При рестарте незавершённые runs становятся FAILED, instanceId меняется; resume нет.

Каждые 500 мс worker выбирает изменившийся run и пересчитывает согласованный снимок
его логов через `RuleBasedAnalysisEngine`. Инциденты доступны уже в ACCEPTING/FINISHING:
новые запросы дополняют evidence и правила, поздние записи могут объединить серии.
ID и detectedAt первого обнаружения сохраняются; при слиянии остаётся наиболее ранняя
идентичность (при равном времени — минимальный UUID). Предварительность определяется
состоянием run; до COMPLETED страницы нужно обновлять с начала. Finish запускает
окончательный проход после получения полных диапазонов; итог фиксируется атомарно.
Период опроса не включает время ожидания очереди и выполнения анализа.

Движок создаёт incidents по пяти
правилам подозрительных входов CS-01–CS-05: всплеск отказов, разные тела запросов,
успех после всплеска, длительная серия и серия с ограничением входа. Пороги,
корреляция и evidence описаны в [правилах Detector](docs/04-events-and-detection.md#реализованные-правила-входа).
Движок использует текущие AccessLog и Incident; ruleset `credential-stuffing-v1`
зафиксирован в коде. Оценку качества обнаружения выполняет Control отдельно.
Доставка полного AccessLog из Gateway/Shop и вызовы оркестрации из Control пока
остаются отдельными задачами.

Интеграционные тесты протокола запускаются с Java 25, Maven и Docker:

```sh
mvn --batch-mode --no-transfer-progress -pl apps/lab-detector -am verify
```

Новые Spring-тесты Detector с БД могут наследоваться от `AbstractDetectorDatabaseTest`
и выбирать нужный режим `@SpringBootTest`. Базовый класс управляет контейнером PostgreSQL,
передаёт настройки runtime/Flyway, предоставляет `runtime` и `admin` JDBC-клиенты и очищает
таблицы перед каждым тестом. После каждого класса Spring-контекст закрывается, чтобы
следующий класс не использовал подключения к уже остановленному контейнеру.
`DetectorTestDatabase` остаётся фабрикой для тестов, которые управляют приложением вручную,
например `DetectorRecoveryIntegrationTest`.

HTTP-тесты наследуются от `AbstractDetectorApiIntegrationTest`: он добавляет общий
`@SpringBootTest`, явно подключённую конфигурацию тестового движка, HTTP-запросы и ожидание
результатов. `HttpClient` принадлежит Spring-контексту конкретного класса и закрывается
вместе с ним. Сценарии разделены на `DetectorRunsIntegrationTest`,
`DetectorIngestionIntegrationTest`, `DetectorFinalizationIntegrationTest`,
`DetectorIncidentsIntegrationTest` и `DetectorSecurityIntegrationTest`.
Фабрики логов, пачек, finish и incidents находятся в `DetectorTestData`; их использует
и recovery-тест. Базовые классы не содержат тестовых сценариев.

## Создание эксперимента

Control предоставляет каталог сценариев, создание и чтение trial по [контракту](contracts/control-api.openapi.yaml).
Нужен Operator Bearer из `LAB_OPERATOR_TOKEN`. Пример запускается в shell контейнера, где задана эта переменная:

```sh
docker compose exec lab-control sh
trial_key=$(cat /proc/sys/kernel/random/uuid)
curl --fail-with-body -i http://127.0.0.1:8080/control/v1/trials \
  -H "Authorization: Bearer $LAB_OPERATOR_TOKEN" \
  -H "Idempotency-Key: $trial_key" \
  -H 'Content-Type: application/json' \
  --data '{"families":["BOLA","REPLAY"],"seed":42}'
curl --fail-with-body http://127.0.0.1:8080/control/v1/trials \
  -H "Authorization: Bearer $LAB_OPERATOR_TOKEN"
exit
```

Новый trial возвращается с 202, повтор с тем же ключом и параметрами - с 200.
Другие параметры с прежним ключом или новый ключ при занятом слоте дают 409.
Правила повторов - в [контракте](contracts/semantics.md#создание-trial).
Ошибки возвращаются как `application/problem+json`.

Пока можно создать только один trial: он остаётся PREPARING с нулевыми счётчиками и `cleanupSafe=false`.
Исполнение сценариев, отмена и безопасная финализация ещё не подключены.
После рестарта trial становится INCONCLUSIVE; ключ и занятый слот сохраняются, новый запуск заблокирован.
Отмена известного trial возвращает 503, отчёт - 409, неизвестный trial - 404.

Для интеграционных тестов нужны Java 25, Maven и Docker:

```sh
mvn --batch-mode --no-transfer-progress -pl apps/lab-control -am verify
```

Тесты используют PostgreSQL 17 через Testcontainers; Docker-сборка выполняет `package` без интеграционных тестов.

[Документация](docs/README.md) | [Архитектура](docs/01-architecture.md) | [ADR runner](docs/adr/0001-runner-inside-control.md)

## Реализации магазина

Один образ shop-api запускается в двух контейнерах. Профиль `protected` выбирает
`lab.shop.secured`, профиль `vulnerable` - `lab.shop.vulnerable`.
Название пакета `secured` используется потому, что `protected` - ключевое слово Java.

```text
lab.shop
├── api           общие контроллеры; DTO из lab.contracts.shop.model
├── application   интерфейсы операций
├── secured       защищённые реализации
└── vulnerable    намеренно уязвимые реализации
```

Без профиля магазина или с обоими профилями запуск завершается ошибкой.
Дополнительные профили, например `test`, допустимы. Клиент не выбирает реализацию.
HTTP-контроллеры реализуют lab.contracts.shop.api.DefaultApi и делегируют операции в application; secured/vulnerable не вызывают друг друга.
Проверки владельца, SQL, checkout и идемпотентность реализуются отдельно в каждом варианте.
Реализованы все девять операций Shop API: `register`, `login`, `getUser`, `searchProducts`, `getCart`, `setCartQuantity`,
`selectCartCoupon`, `checkoutOrder` и `getOrder`. Access JWT (HS256) выдаёт и проверяет сам Shop: подпись, `iss`, `aud`,
`exp`, `nbf`; у каждого режима свои секрет, issuer и audience, поэтому токен другого режима отклоняется.
В `protected` действуют owner-check на профиль и заказ (чужой и отсутствующий дают 404), параметризованный поиск каталога,
строчная блокировка и ограничения корзины (`quantity 1..99`, до 100 позиций), а также транзакционный checkout с порядком
блокировок `key → cart → coupon`, проверкой `Idempotency-Key`/`cartRevision`/`quotedTotal`, атомарным списанием купона и
очисткой корзины. В `vulnerable` эти проверки намеренно отключены для учебных сценариев (`BO-01/02`, `IJ-01`, `RP-01/02`,
`BL-01..04`).
`ShopImplementation.mode()` обозначает вариант, а не наличие работающей защиты.
`/health` предоставляет Actuator; тесты охватывают оба профиля и неверные конфигурации.
Настроены `Flyway` миграции в зависимости от активного Spring профиля. Настроены интеграционные тесты с использованием
`Testcontainers`. Каждый режим API обращается к собственной БД.
По умолчанию тесты запускаются без DataSource, `PostgreSQLIntegrationTest` позволяет проводить тесты с контейнером
PostgreSQL и настроенным окружением.

Для интеграционных тестов нужны Java 25, Maven и Docker:

```sh
mvn --batch-mode --no-transfer-progress -pl apps/shop-api -am verify
```

### JWT

| Параметр                         | Где задаётся                                                | Назначение                                                 |
|----------------------------------|-------------------------------------------------------------|------------------------------------------------------------|
| JWT_SECRET                       | compose: `JWT_SECRET_VULNERABLE` / `JWT_SECRET_PROTECTED`   | Секрет HS256, не короче 32 символов, свой у каждого режима |
| app.jwt.issuer, app.jwt.audience | `application-vulnerable.yaml`, `application-protected.yaml` | Различают токены режимов; значения в профилях разные       |
| app.jwt.ttl                      | `application.yaml`                                          | Срок токена, по умолчанию 15m, допустимо 1…900 секунд      |

Интеграционные и e2e-тесты Shop задают свои значения в `src/test/resources/config/application.yaml`.

### Checkout

| Параметр                      | Где задаётся                                        | Назначение                                                         |
|-------------------------------|-----------------------------------------------------|--------------------------------------------------------------------|
| IDEMPOTENCY_SECRET            | compose: `IDEMPOTENCY_SECRET` (оба экземпляра Shop) | HMAC-ключ для `idempotency_key_hash`, не короче 32 символов        |
| app.checkout.key-lock-timeout | `application.yaml`                                  | Ожидание advisory lock по ключу идемпотентности, по умолчанию `2s` |

## Генерация кода из OpenAPI

[Единые спецификации](contracts/README.md) используются при Docker-сборке и в CI. Сгенерировать Java-интерфейсы API и DTO из корня проекта в том же образе Maven:

```sh
docker run --rm -v "$PWD:/workspace" -w /workspace \
  maven:3.9.11-eclipse-temurin-25 \
  mvn --batch-mode --no-transfer-progress clean generate-sources
```

Код генерируется в `target/generated-sources/<контракт>/src/main/java` соответствующего модуля:

| Модуль | Контракты |
|---|---|
| `libs/lab-contracts` | модели `common`, `control`, `detector`, `runtime` + сериализация/валидация (`lab.contracts.json`) |
| `apps/shop-api` | `shop` (API и модели), `runtime` (API) |
| `apps/lab-gateway` | `runtime` (API) |
| `apps/lab-detector` | `detector` (API) |
| `apps/lab-control` | `control` (API) |

Пакеты `lab.contracts.<контракт>.api` содержат интерфейсы API с HTTP-аннотациями, `lab.contracts.<контракт>.model` - DTO и enum.

Общий `Problem` находится в `lab.contracts.common.model`, общий `Incident` — в `lab.contracts.detector.model`.
Работа с JSON вне контроллеров и область автоматической MVC-валидации описаны в [lab-contracts](libs/lab-contracts/README.md).

У Shop API отключена генерация Bean Validation: ограничения спецификации применяются реализацией с учётом режима. Protected отклоняет нарушения, vulnerable допускает учебные quantity/quotedTotal. Runtime и остальные API сохраняют Bean Validation.

Для компиляции замените `clean generate-sources` на `compile`.

Правки вносятся в [спецификации](contracts/README.md), код пересоздаётся при сборке и не редактируется вручную. Общие настройки OpenAPI Generator находятся в [корневом pom.xml](pom.xml), спецификации для каждого модуля - в его `pom.xml`.

Сгенерированные Java-файлы хранятся в Git. После изменения спецификаций или настроек генератора повторите генерацию и добавьте обновлённые файлы в коммит. [CI](.github/workflows/generated-code.yml) повторяет генерацию на push и pull request и завершается ошибкой при расхождении.

## Каркас gateway и корреляция

`/api/v1/**` на listener `8082` проксируется в `SHOP_PROTECTED_URL` (по умолчанию `http://shop-protected:8080`). Listener `8081` пока возвращает 404 для бизнес-запросов; vulnerable-маршрут добавится отдельной задачей. `/health` работает на обоих портах без X-Request-Id и проверяет сам процесс, а не доступность Shop.

Прокси сохраняет метод, исходный path/query, тело и прикладные заголовки, включая Authorization, Content-Type и Idempotency-Key. Ответ магазина передаётся с исходным статусом и телом; сохраняются Retry-After, WWW-Authenticate, Location, Idempotency-Replayed и повторяющиеся заголовки. Hop-by-hop заголовки (включая перечисленные в Connection) удаляются, Host/Content-Length формируются транспортом. Автоматические retries, redirects, cookie storage и распаковка ответа отключены. Gateway не валидирует бизнес-схему и не применяет login limiter; JWT проверяется до проксирования (см. ниже).

Обязателен ровно один `X-Request-Id` в полном UUID-формате. Корректный ID передаётся в Shop и возвращается клиенту. Отсутствующий/неверный/повторяющийся заголовок даёт 400/INVALID_REQUEST без обращения к upstream; для самого отказа создаётся один UUID, общий для ответа и лога. В Shop действует тот же контракт. `/internal/**` не проксируется; служебный Runtime API пока не реализован.

Gateway и оба Shop пишут JSON-логи `http_request`: `service`, `trace_id`, `method`, `path`, `status`, `duration_ms`. Здесь `trace_id` — тот же X-Request-Id в MDC, отдельного ID не генерируется. В `path` нет query; токены, пароли, тела и заголовки не записываются. Это минимальные диагностические логи, ещё не полный AccessLog/LogBatch для Detector. Фильтр работает для синхронных `/api/v1` запросов после допуска HTTP-парсером; ошибки самого парсера до servlet-фильтров сюда не входят.

| Параметр | По умолчанию | Назначение |
|---|---|---|
| SHOP_PROTECTED_URL | http://shop-protected:8080 | Фиксированный HTTP origin, без path/query/userinfo |
| GATEWAY_CONNECT_TIMEOUT | 2s | Установление соединения |
| GATEWAY_UPSTREAM_TIMEOUT | 5s | Общий deadline обращения к upstream, включая чтение тела и ожидание соединения из пула |
| GATEWAY_MAX_BODY_BYTES | 8388608 | Максимальный буфер тела запроса/ответа; допустимо до 16 MiB |

Недоступный upstream даёт 502/UNAVAILABLE, таймаут — 504/UNAVAILABLE. Слишком большой запрос — 413/INVALID_REQUEST, ответ upstream сверх бюджета — 502/UNAVAILABLE. Все эти ответы содержат X-Request-Id и Problem.requestId. Таймаут не означает откат заказа, proxy не повторяет операцию. Бюджеты — технические ограничения транспорта, а не экспериментальные политики.

Тесты запускаются при Docker-сборке. Gateway integration tests используют локальный тестовый HTTP upstream: методы, query/body/headers, ID, ответы 429/redirect, disconnect без retry, timeout заголовков/тела, ограничения буфера и listener, отказ JWT без обращения к upstream и без токена в логах. `JwtVerifierTest` проверяет каждую причину отказа. Shop HTTP-тесты проверяют тот же ID в ответе/логе обоих профилей, 400/500, отсутствие секретов и восстановление MDC. Тестовые ручки существуют только в `src/test`.

### Проверка JWT в Gateway

На listener `8082` Gateway проверяет `Authorization: Bearer <JWT>` до чтения тела и обращения к upstream. Правила те же, что у protected Shop: только HS256, подпись секретом `JWT_SECRET_PROTECTED`, обязательный `exp`, `nbf`, допуск часов 5 секунд, `iss` равен `urn:lab:shop:protected`, `aud` содержит `shop-protected`. Issuer и audience заданы в `apps/lab-gateway/src/main/resources/application.yaml` и должны совпадать с `application-protected.yaml` Shop.

Без токена проходят только точные пути `/api/v1/auth/login` и `/api/v1/auth/register`. Сравнивается исходный URI, поэтому варианты с `%`-кодированием, `;` или `..` требуют токен. Невалидный токен даёт 401/UNAUTHORIZED с `WWW-Authenticate: Bearer`, запрос в Shop не уходит. Gateway не извлекает userId и не заменяет проверку в Shop: валидный токен передаётся без изменений, и Shop проверяет его повторно.

Каждое решение пишется в [журнал решений](#журнал-решений) с `policyId=gateway.jwt` и `kind=SECURITY`: ALLOW с причиной `VALID_TOKEN` или `PUBLIC_ROUTE`, BLOCK с одной из причин ниже. Значение токена и заголовка Authorization не записывается, причина в ответ не попадает.

| reason | Когда |
|---|---|
| MISSING_TOKEN | Нет заголовка Authorization |
| INVALID_AUTHORIZATION_HEADER | Несколько заголовков, схема не Bearer или недопустимые символы |
| MALFORMED_TOKEN | Значение не разбирается как JWT |
| UNSUPPORTED_ALGORITHM | `alg` не HS256, в том числе `none` и JWE |
| INVALID_SIGNATURE | Подпись не совпадает, например токен vulnerable Shop |
| MISSING_EXPIRATION | Нет `exp` |
| TOKEN_EXPIRED | `exp` в прошлом с учётом допуска |
| TOKEN_NOT_YET_VALID | `nbf` в будущем с учётом допуска |
| INVALID_ISSUER | `iss` отсутствует или другой |
| INVALID_AUDIENCE | `aud` не содержит `shop-protected` |

## Журнал решений

Gateway и оба Shop дописывают решения в `/app/logs/decisions.jsonl`, каждый в свой Docker volume. В строке указаны `requestId`/`trace_id`, `policyId`, `decision`, `reason`, время и вид проверки. Формат и подключение новых политик описаны в [документации событий](docs/04-events-and-detection.md#локальный-журнал-решений-политик).

```sh
docker compose exec -T lab-gateway tail -n 20 /app/logs/decisions.jsonl
docker compose exec -T shop-protected tail -n 20 /app/logs/decisions.jsonl
docker compose exec -T shop-vulnerable tail -n 20 /app/logs/decisions.jsonl
```

Каталог сохраняется при пересоздании контейнера; `docker compose down --volumes` удаляет журналы вместе с данными БД. Ротация: 10 MB/день, архивы до 7 дней и 100 MB. `DECISION_LOG_PATH` задаёт полный путь файла; каталог должен быть доступен на запись пользователю сервиса.

Сейчас журнал охватывает технические проверки request ID, маршрута и размера запроса и проверку JWT в Gateway. Экспериментальные политики Shop подключаются через `DecisionLog.record` по мере реализации. ALLOW относится к одной проверке, а итоговый HTTP-ответ смотрят в access-логе.
