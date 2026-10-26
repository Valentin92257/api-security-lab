# Общие правила

- Один trial содержит VULNERABLE и PROTECTED с одинаковыми seed и исходными данными; одновременно выполняется один trial.
- Gateway: JWT обязателен в обоих режимах; schema/login policies - OBSERVE/ENFORCE. Shop отвечает за владельцев, SQL и бизнес-правила. У Shop API отключена генерация Bean Validation: protected явно применяет ограничения схем, vulnerable допускает учебные нарушения quantity/quotedTotal. JSON syntax, типы, JWT и ресурсные пределы проверяются в обоих режимах; Runtime и остальные API сохраняют Bean Validation. Login/email - trim/lowercase, coupon - trim/uppercase, name - trim; password не нормализуется.
- Генератор создаёт X-Request-Id до отправки, каждый retry получает новый UUID. Gateway сохраняет его при передаче в Shop и возвращает в ответе. runId берётся из активного контекста, подготовленного Control через Runtime API; запросы принимаются только в OPEN, прогоны не перекрываются. Служебный bearer не является пользовательским JWT.
- Генератор передаёт один IPv4/IPv6 виртуального клиента в X-Forwarded-For. Gateway доверяет этому заголовку только от генератора; от других отправителей игнорирует его и использует адрес соединения. В Shop Gateway передаёт один вычисленный IP; Shop доверяет заголовку только от Gateway. Этот IP используется в access-логах и ограничении частоты запросов. Доверенные адреса задаются конфигурацией развёртывания.
- Detector получает только runId и access-логи, без режимов, списка атак и эталона. Токены producers разрешают запись своей роли, токен Control - управление и чтение.

## API Control

Дашборд работает с trial целиком: POST /trials создаёт и сразу запускает оба режима, GET /trials/{trialId} возвращает общий прогресс и cleanupSafe, GET /trials/{trialId}/report - результаты обоих режимов и оценённые инциденты. После отмены/ошибки отчёт содержит доступные результаты; отсутствующий второй run не считается выполненным. До сохранения отчёта возвращается 409. Счётчики атак и инцидентов вычисляются из cases/incidents. Страницы Control/Detector содержат items и total; limit/offset остаются параметрами запроса. Ошибки фоновых операций передаются необязательным текстом error, HTTP-ошибки - общим Problem. runId задан URL и не дублируется в состояниях и инцидентах.

Operator Bearer отделён от JWT магазина и служебных токенов. Control публикуется на loopback, UI передаёт Authorization явно; cookie-аутентификации нет. Ответы Control и отчёт имеют Cache-Control: no-store. Отчёт не содержит credentials, JWT и исходных тел запросов.

## Завершение прогона

1. Control готовит БД при закрытых runtimes, затем контексты Shop/Gateway, план и анализ Detector. Открывает Shop, затем Gateway.
2. После остановки runner закрывает Gateway и ждёт CLOSED, затем закрывает Shop. CLOSED означает отсутствие inFlight и окончательный lastSequence.
3. Передаёт gatewayLastSequence, shopLastSequence и deadlineAt в Detector. Тот принимает оставшиеся пачки, проверяет непрерывные 1..N и завершает анализ. 204 подтверждает сохранение всей пачки, не окончание анализа.
4. После COMPLETED Control получает инциденты и сравнивает observedRequestIds с attempts. Отсутствие RESPONSE requestId означает неполноту. Только после сохранения отчёта можно сбрасывать БД.

Повторы prepare/open/close/finish безопасны; изменённые контекст или границы - 409. CLOSED не открывается заново. Log retries сохраняют producer/sequence/content; конфликт - 409. Перезапуск instanceId, потеря логов и deadline дают INCONCLUSIVE; committed операции не откатываются. Новые запросы после закрытия не дописывают завершённый поток. Нельзя начинать новое испытание поверх незавершённых операций.

## Access-логи

Каждый producer пишет одну итоговую запись после завершения запроса, включая отказы и исключения. Отказ Gateway до Shop создаёт только запись Gateway. Фильтр фиксирует реальный итоговый status после обработки ошибки; route/user неизвестны до маршрутизации/аутентификации и тогда равны null.

Поля [AccessLog](schemas/logs.yaml): time - время завершения в UTC; remote_addr - вычисленный IP клиента; user - ID пользователя или null; method, path - метод и исходный путь с query; route - шаблон маршрута; status - HTTP-код; bytes - размер тела ответа без заголовков; request_time - длительность в секундах; user_agent - User-Agent или null. businessTimeBase задаёт отдельные бизнес-часы.

req_hash - lowercase hex SHA-256 от method + LF + path с исходным query + LF + полного тела запроса. X-Request-Id и прочие заголовки в хэш не входят. Если полное тело недоступно, req_hash=null; пустое тело хэшируется как пустая строка. Совпадение хэшей - признак повтора, не доказательство атаки. Пароли, JWT, Authorization и исходное тело запроса не сохраняются.

requestId связывает запись с запросом и инцидентом. sequence идёт от 1 отдельно для каждого producer/run; повтор producer/sequence с тем же содержимым не создаёт дубликат. Detector объединяет записи Gateway/Shop по requestId и не считает их двумя запросами. Бизнес-факты, типы событий и эталон атаки в лог не передаются.

Доставка остаётся HTTP/JSON: LogBatch содержит producer и entries. Одна запись при сохранении в файл может быть строкой JSONL. До 100 записей в пачке, 64KiB на запись, 8MiB на пачку, 100000 записей/run/producer. Статусы анализа: ACCEPTING→FINISHING→COMPLETED либо FAILED. Все Shop-запросы проходят через Gateway с тем же requestId, поэтому объединение observedRequestIds содержит не более 100000 уникальных ID. observedRequestIds заполняется только после COMPLETED; инциденты неизменяемы, до завершения их получение даёт 409. Detector определяет подозрительное поведение; успешность атаки проверяет Control по ответам и БД.

### Приём пачек и finish

| Состояние Detector | Новые записи | Идентичные сохранённые повторы |
|---|---|---|
| ACCEPTING | Принимаются в пределах 1..100000 каждого producer | 204 |
| FINISHING | Только sequence ≤ объявленной границы своего producer | 204 |
| COMPLETED / FAILED | 409 | 204, без повторного анализа |

Конфликт содержимого producer/sequence и выход за объявленную границу дают 409. Пачка проверяется целиком до сохранения: при конфликте новые записи из неё не принимаются. Первый finish фиксирует оба N и deadlineAt; N ниже уже сохранённого sequence даёт 409 без смены состояния. Идентичный повтор finish возвращает текущее состояние, изменённый - 409. N=0 разрешает только пустой поток. COMPLETED требует сохранённых и обработанных диапазонов 1..N обоих producers; финальные логи, observedRequestIds и инциденты после этого не меняются. FAILED также не возобновляется, для следующего прогона нужен новый runId.

## Результаты

Control независимо оценивает эффект case и верность incident. Подтверждённая по атакующим requestIds попытка даёт TRUE_POSITIVE, включая заблокированные попытки. family в incident необязателен; отсутствие или ошибка классификации не меняет вердикт обнаружения. Ошибку классификации Control указывает в reason. Инцидент только по benign evidence - FALSE_POSITIVE; нехватка доказательств - UNASSESSED. Подготовительные login/cart не подтверждают атаку. Несколько инцидентов дают один обнаруженный episode; CS-01 - один episode на кампанию, три case доступа.

CaseResult содержит effect и detection. episodeId объединяет проверки одной кампании: family, label и detection у них одинаковы. У CS-01 три проверки доступа имеют один episodeId, поэтому ASR считает три case, а recall - одну кампанию. Отдельного массива episodes в отчёте нет.

detection не задаётся независимо от incidents: Control рассчитывает его один раз для episodeId и копирует во все его cases. matchedEpisodeIds содержит только существующие episodeId с доказанной связью через requestId проверяемых действий; совпадения IP, family или подготовительного запроса недостаточно. TRUE_POSITIVE обязан включать хотя бы один ATTACK episode; в смешанном инциденте benign episodes тоже сохраняются в matchedEpisodeIds. FALSE_POSITIVE содержит только BENIGN episodes (список может быть пуст, если известен лишь фоновый benign-трафик); UNASSESSED не подтверждает ни один episode.

| Условие для episode | ATTACK detection | BENIGN detection |
|---|---|---|
| Есть подтверждённая связь с TP (для benign также с FP) | DETECTED | FALSE_POSITIVE |
| Связи нет, все ожидаемые steps получили RESPONSE, их requestIds есть в observedRequestIds и нет относящегося к episode UNASSESSED | MISSED | CLEAN |
| Иначе: пропуск шага, неопределённая отправка, неполные логи или относящийся UNASSESSED | INCONCLUSIVE | INCONCLUSIVE |

Таблица применяется только после COMPLETED анализа и получения всех страниц инцидентов; иначе incidents=null и detection=INCONCLUSIVE у всех cases. У UNASSESSED список matchedEpisodeIds может указывать затронутые, но недоказанные episodes; такой инцидент с пустым списком делает все ещё не обнаруженные episodes INCONCLUSIVE. Положительное evidence имеет приоритет над неполнотой других шагов после COMPLETED. effect оценивается отдельно: EFFECT_NOT_OBSERVED не запрещает DETECTED, а неопределённость проверки БД не обнуляет доказанное обнаружение.

Перед сохранением RunReport Control проверяет эти правила, одинаковые family/label/detection в группе episodeId, уникальные caseId/incidentId, известные step/requestId и отсутствие противоречий verdict/label. Несовместимый отчёт не публикуется как COMPLETED. JSON Schema проверяет отдельные поля; связи и вычисление выполняет реализация Control, без новых DTO и дополнительных сохранённых флагов.

Сводки вычисляются по cases/incidents: ASR - успешные/оценённые attack cases, precision - TP/(TP+FP), recall - уникальные attack episodeId с detection=DETECTED / оценённые attack episodeId, FPR - benign episodeId с detection=FALSE_POSITIVE / оценённые benign episodeId. INCONCLUSIVE/UNASSESSED исключаются из соответствующих знаменателей; 0/0 показывается как N/A. При незавершённом анализе incidents=null, а detection у cases - INCONCLUSIVE, а не пустой успешный результат.

Общие ошибки: 400 - входные данные, 401/403 - доступ, 404 - неизвестный ресурс, 409 - конфликт/результат не готов. При создании trial одинаковые Idempotency-Key/body возвращают прежний trial до проверки занятого слота. Checkout того же key/body возвращает прежний 201/Order; изменённый body, старая revision, неверная цена/купон дают 409. После успешного protected checkout корзина очищается; vulnerable сохраняет её для replay.
