# API Security Lab: от запуска до отчёта

Обзор согласован с [итоговыми контрактами](../contracts/README.md). Код сервисов и измерения ещё предстоят; интерфейсы API и DTO уже генерируются из OpenAPI.

## Цель и границы

Локальный учебный стенд воспроизводит атаки на синтетическое API магазина и сравнивает два фиксированных режима: vulnerable и protected. Результат включает эффект атаки, подозрения Detector и независимую оценку этих подозрений Control. Все данные синтетические, внешние цели не используются.

Инвариант - правило корректности состояния: заказ принадлежит пользователю, цена задаётся сервером, купон имеет срок/лимит, одна операция создаёт не более одного заказа. Он проверяется отдельно от HTTP-статуса и сообщения детектора.

| Семейство | Что моделируем | Как Control проверяет эффект |
|---|---|---|
| CREDENTIAL_STUFFING | Серия синтетических login/password | Полученный токен действительно открывает профиль жертвы |
| BOLA | Подмена ID профиля/заказа | В ответе вернулись чужие защищаемые поля |
| REPLAY | Повтор checkout | Появился дополнительный заказ на ту же версию корзины |
| INJECTION | SQL-подобный q в поиске | В выдачу попал скрытый контрольный товар |
| BUSINESS_LOGIC | Количество, купоны, цена и конкурентный checkout | Нарушен соответствующий бизнес-инвариант |

Каждое семейство имеет benign-контроли: свой заказ, обычный поиск, допустимый купон, нормальный вход и технический retry. Без них нельзя измерить ложные срабатывания. Полный [каталог cases](05-scenarios-and-experiments.md) сохраняет business subcases и границы оценки.

## Компоненты

Четыре Java-приложения образуют пять процессов: Shop запускается дважды. Generator/Runner, подготовка БД и Evaluator находятся внутри Control.

```text
Оператор -> Control [Runner, Fixture, Evaluator] -> Gateway -> выбранный Shop -> его БД
                                                  |             |
                                                  +---- логи ---+-> Detector -> свои логи/incidents
Control -> Runtime API Shop/Gateway
Control -> Detector API -> итоговые incidents
Control: план + attempts + readonly Shop + incidents -> TrialReport -> UI
```

Gateway принимает vulnerable на 8081, protected на 8082. Backend не выбирается произвольным заголовком/URL. Shop обеспечивает бизнес-транзакции. Detector не стоит на пути обработки покупки, хранит логи у себя и не читает БД магазина, сценарий или эталон. Техническая наблюдаемость LGTM отделена от доставки данных Detector.

## API и организация кода

| Контракт | Кто вызывает |
|---|---|
| [Shop](../contracts/shop-api.openapi.yaml) | Runner через Gateway |
| [Control](../contracts/control-api.openapi.yaml) | Дашборд/оператор |
| [Runtime](../contracts/runtime-api.openapi.yaml) | Control → Shop/Gateway |
| [Detector](../contracts/detector-api.openapi.yaml) | Shop/Gateway и Control → Detector |

Java 25, Maven Wrapper, Spring Boot/MVC, PostgreSQL и явные SQL-транзакции. Версии подключённых библиотек фиксируются в pom.xml. Четыре apps-модуля получают сгенерированные интерфейсы/DTO в target/generated-sources. Бизнес-реализация пишется отдельно. [Порты модулей Control](../contracts/modules.md) являются внутренними вызовами Java.

Магазин имеет девять операций: register, login, профиль по ID, поиск товаров, чтение корзины, установка количества/купона, создание и чтение заказа. Карточки товара, истории заказов, удаления позиций, платежей и возвратов нет. Начальные данные Fixture-модуль Control готовит прямо в БД; для этого не создаётся Shop HTTP endpoint.

## Два режима защиты

JWT обязателен в обоих режимах, кроме register/login. Он многократен. Schema/login policies Gateway работают в OBSERVE для vulnerable и ENFORCE для protected. В Shop выбираются соответствующие owner-check, SQL и бизнес-стратегии. Отдельных переключателей политик в API дашборда нет.

Protected проверяет owner, параметризует поиск, рассчитывает цену на сервере и атомарно расходует купон. Checkout принимает cartRevision/quotedTotal и Idempotency-Key. Тот же успешный key/body возвращает прежний 201/Order; другое тело или новая попытка оформить уже потреблённую revision дают 409. Запись заказа, купон и очистка корзины согласованы транзакцией. [Данные и инварианты](02-api-and-data.md).

Первая корректная пара credentials и новые допустимые операции с украденным JWT остаются рисками модели. Отсутствие инцидента не означает отсутствие атаки, наличие инцидента не доказывает её успех.

## Запуск испытания

1. UI читает GET `/control/v1/scenarios` и выбирает одно или несколько из пяти семейств.
2. POST `/control/v1/trials` с Idempotency-Key, families и необязательным seed сразу запускает испытание. Тот же ключ/body возвращает прежний trial.
3. Control резервирует один слот, готовит исходный snapshot/БД и запускает vulnerable, затем protected с теми же seed и businessTimeBase.
4. UI опрашивает GET `/control/v1/trials/{trialId}`. POST `.../cancel` прекращает новые шаги, но не откатывает завершённые операции.
5. GET `.../report` возвращает общий TrialReport после сохранения; до готовности 409. При отмене/ошибке возможен частичный отчёт.

Параметры trial неизменяемы. Новый запуск запрещён при занятом слоте или cleanupSafe=false. Прогоны не перекрываются, но внутри case возможны конкурентные запросы. Потеря UI-вкладки не прекращает работу Control.

## Контекст запросов и виртуальные IP

Runner создаёт X-Request-Id до каждой отправки. Gateway сохраняет его при проксировании и возвращает в ответе; retry получает новый UUID. Это связывает attempts, логи и incidents даже при потере ответа.

Для каждого виртуального клиента Runner задаёт постоянный IP в X-Forwarded-For. Gateway доверяет заголовку только от генератора, Shop - только от Gateway. IP используется в rate limiter и remote_addr; JWT определяет пользователя. Реальный сетевой адрес соединения при такой имитации не меняется.

RunId берётся из активного Runtime-контекста, подготовленного Control. Служебные команды имеют отдельный Bearer; operator Bearer и JWT магазина не дают эти права. Бизнес-запросы не несут отдельного run token.

## Логи и обнаружение

У каждого producer одна итоговая access-запись на запрос: время, IP, пользователь, метод, path с query, route, status, bytes, request_time, user_agent, req_hash и корреляционные sequence/requestId. При отказе Gateway до Shop есть только запись Gateway. [Формат и доставка](04-events-and-detection.md).

Логи отправляются непосредственно в Detector пачками HTTP/JSON. ACK подтверждает хранение, повторы producer/sequence с тем же содержимым не удваиваются. req_hash учитывает конкретный path/query и полное тело; он не сохраняет исходное тело. Повтор хэша может быть техническим retry.

Detector анализирует HTTP-признаки: серии login-ошибок, перебор ID, подозрительный query, повторы и частоту. Он не получает владельца объекта или ground truth. По access-логам нельзя гарантированно распознать каждый business abuse или отличить злонамеренный повтор от обычного. Это ограничение учитывается при измерении.

Incident содержит incidentId, detectedAt, requestIds, description и необязательный family. Он описывает подозрение на попытку. Control получает только итоговые инциденты и независимо оценивает их; редактирования статуса инцидента оператором нет.

## Завершение и сбои

Runtime: PREPARED → OPEN → CLOSING → CLOSED либо ERROR. Detector: ACCEPTING → FINISHING → COMPLETED либо FAILED. Trial: PREPARING → RUNNING → FINALIZING → COMPLETED; альтернативы FAILED/CANCELLED/INCONCLUSIVE.

После completion Runner и сохранения attempts Control закрывает Gateway, затем Shop. CLOSED означает inFlight=0 и окончательный lastSequence. Control передаёт обе границы Detector, ждёт непрерывные 1..N и COMPLETED, сверяет observedRequestIds с attempts, читает incidents и выполняет Evaluator. Только затем допустима следующая подготовка БД.

Client timeout не означает остановку SQL. Перезапуск instanceId, потеря лога или deadline дают INCONCLUSIVE. Продолжения прерванного run нет; уже committed заказы не откатываются. Фиксированное ожидание sleep не заменяет протокол завершения.

## Эталон и отчёт

Control хранит приватный план с actors/owners, labels и attack-specific steps до запуска. Evaluator использует план, FixtureData, attempts/responseFacts и readonly состояние Shop. Он не вызывает production calculator и не принимает HTTP-отказ как доказанное предотвращение.

Case effect: EFFECT_OBSERVED/EFFECT_NOT_OBSERVED/INCONCLUSIVE. IncidentAssessment: TRUE_POSITIVE/FALSE_POSITIVE/UNASSESSED с matchedEpisodeIds и reason. Обнаруженная заблокированная попытка может быть TP. Ошибка или отсутствие family не отменяют обнаружение; подготовительный login не подтверждает атаку.

TrialReport содержит оба режима, cases с episodeId/detection и оценённые incidents. Дашборд вычисляет ASR, precision, recall и benign FPR; несколько incidents не увеличивают число обнаруженных episodes. 0/0 показывается как N/A. При незавершённом анализе incidents=null, а detection у cases - INCONCLUSIVE. Отчёт не содержит паролей, JWT, raw body, отдельного массива policy decisions или встроенного объекта metrics.

## Данные, команда и реализация

Два Shop используют свои семь таблиц. Fixture-доступ Control административный и применяется только при закрытом приёме. Evaluator readonly. Detector имеет только собственное хранилище; общих SQL jobs или проекции наблюдений нет. Физические таблицы Control/Detector ещё не являются согласованным интерфейсом.

Р1 реализует Shop/данные, Р2 - Gateway/защиту, Р3 - Runner, Р4 - Detector/Control/UI. Первая интеграция соединяет BO-01 и benign-контроль со всеми четырьмя компонентами; затем добавляются остальные семейства и конкурентность. [План приёмки](06-implementation-and-testing.md) отличает будущие требования от уже выполненных проверок генерации.

Интерактивный запуск публикует UI/Grafana на loopback. Внешние цели, Docker socket и произвольный egress сценарного клиента не нужны. Compose и фактические команды запуска документируются после реализации; наличие OpenAPI само по себе не означает работающий стенд.
