# Реализация и приёмка

## Порядок разработки

| Этап | Работа | Критерий выхода |
|---|---|---|
| 0. Контракты | Wire schemas, API/errors, версии зависимостей; spikes validator, локальный limiter, SQL grants | Примеры валидны, зависимости собираются, используемые OpenAPI keywords проверены |
| 1. Skeleton | Maven modules, Compose, три DB и роли, keys, health, logs/traces, служебные interfaces | Запрос через gateway; ScenarioHttpClient допускает только gateway, внешний egress control закрыт, detector не читает labels |
| 2. Вертикальный BOLA | Fixture/users/orders, BO-01 и benign, JSONL writer/collector/audit, одно правило, oracle, UI/report | Доказана утечка vulnerable, protected сохраняет owner; есть обнаружение при 200 и JSON-отчёт |
| 3. API и защита | Полный API, catalog canary, checkout/idempotency/coupon, schema/rate policies | Общие benign contract tests двух режимов; согласованные фикстуры и атомарный checkout |
| 4. Все угрозы | CS/BO/RP/IJ/BL со всеми subcases, benign episodes, windows/incidents | Для каждого случая есть effect criterion, oracle, evidence и результат |
| 5. Достоверность | DRAINING/STREAM_END/CLOSE, in-flight, log gaps/duplicates, timeout при живом сервере, безопасный reset | Потеря доказательств не даёт достоверный зелёный отчёт; сбой делает run INCONCLUSIVE без продолжения |
| 6. Сдача | Paired series/seeds, performance/cold start, export, runbook, схемы, вклад | Воспроизводимый запуск, regression reports, демонстрация и документация реализации |

Порядок отражает зависимости, календарный план уточняется после первого сквозного результата. Исследования OpenFGA, Schemathesis или PyOD начинаются после обязательного набора и не блокируют его.

## Первая интеграция четырёх участников

Р1 делает собственный/чужой order, fixture API, две стратегии owner-check, JSONL writer и readonly данные для oracle. Р2 делает два listener, JWT, единые errors, JSONL writer и сетевые проверки. Р3 делает BO-01/benign plans, Java prepare/start/cancel и AttemptSink, HTTP execution и step results. Р4 соединяет log-ingestor, control states, detector view, правило, evaluator и минимальный UI/report.

Общие контракты имеют owner и совместное ревью: API — Р1+Р2; policy/event — Р2+Р4; job/steps — Р3+Р4; oracle — Р1+Р3+Р4. Р4 не должен самостоятельно реализовывать все интеграционные адаптеры: fixture/oracle data предоставляет Р1, admission/STREAM_END — Р2, модуль исполнения и cancellation — Р3.

## Проверки реализации

| Область | Обязательная проверка |
|---|---|
| API/schema | Один spec для обоих режимов; positive examples, unknown/duplicate fields, types/bounds/nullability/query, body forwarding |
| JWT/BOLA | Неверные alg/signature/iss/aud/exp/nbf; valid token многократен; чужие profile/order и отсутствующие дают 404, свои доступны |
| Login limits | Параллельный consume/backoff; OBSERVE/ENFORCE/run isolation; OFF без изменения state; безопасный TTL; overflow 503 + INCONCLUSIVE; старый callback не меняет новый run; restart не продолжает опыт |
| Replay | Same key/body, altered body, оформленная cart revision/new key, параллельные key, expiry купона, cart revision, retry после потери ответа/commit |
| Бизнес-транзакция | Реальные cart/coupon races, rollback на критичных шагах, один эффект, expiry equality, серверная цена и независимый пересчёт |
| Injection | Реальный canary leakage vulnerable, отсутствие leakage protected, literal punctuation/search и sort allowlist |
| Security logs | JSONL/schema/redaction; запись после commit/rollback; пары started/completed; seq/END; identical duplicate/conflict; partial line; oversized event; отсутствие sampling/rotation/drop |
| Detector | Runtime SQL grants, YAML whitelist, поздний commit меньшего id, atomic state/incident/marker, out-of-order/ties, CLOSE/retraction; отсутствие повторного вклада при polling |
| Orchestration | Client timeout при продолжающемся SQL, deadline/cancellation и stale epoch, missing terminal/STREAM_END, запрет reset до quiescence |
| Исполнение/UI | Один активный run; UI/cancel доступны при максимальном бюджете runner; отдельные executors; bounded results; остановка без новых retry после cancel/deadline |
| Метрики/UI | N/A при нуле, incomplete не FN, wrong class FP, несколько incidents на episode, benign retry, escaping/CSRF и отсутствие секретов |
| Runtime | Внешний egress control закрыт; ScenarioHttpClient отвергает host/direct backend/management; ArchUnit проверяет зависимости модулей; scoped credentials, INCONCLUSIVE без resume после restart detector/control, measured startup/resource limits |

SQL locks/grants/concurrency проверяются Testcontainers с PostgreSQL, не H2. Unit tests подходят parser/policies и чистым функциям. Независимая state model для add/change/coupon/checkout/retry проверяется jqwik; production calculator не используется как expected model. Конкурентные interleavings дополнительно задаются barriers.

Планируемый pipeline `./mvnw verify`: compile → unit/schema/contract → PostgreSQL integration → компактный security smoke. Полная приёмочная серия — `./mvnw -Pacceptance verify` с фиксированными seed/budget/config и сохранёнными reports. Обе команды выполняются в CI; после появления реализации они должны совпадать с её фактическими Maven profiles.

Regression падает при нарушении обещанного protected-инварианта, неподтверждённом vulnerable baseline, несовместимых snapshots, потере обязательного evidence, нарушении изоляции или утечке секрета. Гонки проверяются серией; отсутствие воспроизведения в серии не выдаётся за успешную демонстрацию. CS-02 и другие объявленные остаточные риски оцениваются по их реальной гарантии.

## Покрытие функциональных требований

Коды служат идентификаторами приёмки; содержание требований дано здесь полностью в пределах указанных групп. Все обязательны, кроме индивидуального переключения политик FR-29 с приоритетом S.

| Требования | Проверяемый результат |
|---|---|
| FR-1–FR-3 | Атомарная регистрация пользователя/корзины, login/JWT и текущий профиль |
| FR-4–FR-9 | Каталог/карточка, все операции корзины, checkout, купон со сроком/лимитом/одним применением на order, история; один контракт двух режимов |
| FR-10–FR-16 | Активы/угрозы/меры/остаточные риски; credential stuffing, BOLA profile/order, replay, injection, четыре business subcases; отдельный критерий эффекта и инвариант |
| FR-17–FR-20 | Создание experiment с scenarios/targets/actors/policies; подготовка/шаги/результаты; seed/manifest; безопасный reset |
| FR-21–FR-22 | Обязательный gateway; JWT claims/signature и одноразовость бизнес-операции при многократном access JWT |
| FR-23–FR-28 | Schema и неизвестные поля; owner-check; source/account limits/backoff; SQL-параметризация; серверная цена/idempotency/atomic coupon; структурированный лог allow/block/flag с причиной |
| FR-29 | Отдельные modes политик для нового run; обязательные эксплуатационные проверки не отключаются |
| FR-30–FR-35 | Общий event; независимый detector незаблокированных попыток; UI/filters/card/steps/reasons; TP/FP/FN; сравнение эффектов/нарушений/детекции/FP; JSON-export |
| FR-36–FR-39 | Benign workload для FP; централизованные structured logs; trace runner→gateway→shop→detector; redaction секретов |

## Нефункциональная приёмка

| Код | Условие готовности |
|---|---|
| NFR-1 | Внешний egress control закрыт сетевыми тестами; сценарный HTTP-клиент отвергает внутренние цели кроме gateway. Внутри общей JVM нет отдельной сетевой изоляции runner |
| NFR-2 | Одна Compose-команда на чистой описанной машине; cold readiness ≤10 минут, downloads учтены, warm time отдельно |
| NFR-3 | Только synthetic data, локальная генерация keys, safe env example и отсутствие реальных секретов |
| NFR-5 | API пишет локальные security-логи без синхронного вызова detector/control; отставание обработки допустимо до deadline. Restart/отказ процесса делает run INCONCLUSIVE, восстановления run нет |
| NFR-6 | Java modules, typed contracts и versioned config, а не несвязанные скрипты |
| NFR-7 | Автоматические проверки одной командой и в CI, сохранённые reports |
| NFR-8 | README, архитектура/схемы, решения и tradeoffs, API/сценарии/метрики, описание фактического вклада |
| NFR-9 | Из UI доступны создание/start/cancel, сравнение, карточки и export; отдельная дизайн-проработка не требуется |

## Демонстрация и сдача

Поднять стенд и показать health/версии/роли; выполнить BO-01 на двух режимах; показать response, owner oracle, incident и decision; продемонстрировать checkout retry/race по числу эффектов; выполнить пять семейств с benign traffic и открыть сравнение со знаменателями. Затем показать отставание обработки при работающем detector и корректное ожидание DRAINING; для пропущенного terminal/END или перезапуска процесса показать INCONCLUSIVE без возобновления run. Завершить JSON-export и traces с объяснением остаточных рисков.

Результат сдачи: исходный код в публичном репозитории, воспроизводимые команды, актуальные схемы и документация, тестовые отчёты, видео либо сценарий демонстрации и фактический вклад каждого участника. До выполнения проверок проектная спецификация не считается доказательством готового стенда.

Проверки упрощённого checkout: новый key для оформленной revision возвращает CART_ALREADY_CHECKED_OUT; устаревшая неоформленная revision — CART_CHANGED. Успешный retry после очистки корзины и истечения купона возвращает прежний Order. Два разных key одной revision создают один заказ; vulnerable replay той же версии создаёт повторный эффект. Изменение замороженного каталога/условий купона приводит к INCONCLUSIVE, а не к корректному результату ASR.
