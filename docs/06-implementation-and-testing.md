# Реализация и приёмка

## Порядок разработки

| Этап | Работа | Критерий выхода |
|---|---|---|
| 0. Контракты | Wire schemas, API/errors, версии зависимостей; spikes validator, Redis adapter, SQL grants | Примеры валидны, зависимости собираются, используемые OpenAPI keywords проверены |
| 1. Skeleton | Maven modules, Compose, три DB и роли, keys, health, logs/traces, служебные interfaces | Запрос через gateway; runner не достигает shop/SQL/внешних целей, detector не читает labels |
| 2. Вертикальный BOLA | Fixture/users/orders, BO-01 и benign, outbox/audit, одно правило, oracle, UI/report | Доказана утечка vulnerable, protected сохраняет owner; есть обнаружение при 200 и JSON-отчёт |
| 3. API и защита | Полный API, catalog canary, checkout/idempotency/coupon, schema/rate policies | Общие benign contract tests двух режимов; согласованные фикстуры и атомарный checkout |
| 4. Все угрозы | CS/BO/RP/IJ/BL со всеми subcases, benign episodes, windows/incidents | Для каждого случая есть effect criterion, oracle, evidence и результат |
| 5. Достоверность | DRAINING/CLOSE, leases, late commits, duplicate/loss, crashes, fencing/reset | Потеря доказательств не даёт достоверный зелёный отчёт; restart не дублирует эффект |
| 6. Сдача | Paired series/seeds, performance/cold start, export, runbook, схемы, вклад | Воспроизводимый запуск, regression reports, демонстрация и документация реализации |

Порядок отражает зависимости, календарный план уточняется после первого сквозного результата. Исследования OpenFGA, Schemathesis или PyOD начинаются после обязательного набора и не блокируют его.

## Первая интеграция четырёх участников

Р1 делает собственный/чужой order, fixture API, две стратегии owner-check, outbox и readonly данные для oracle. Р2 делает два listener, JWT, единые errors, audit writer и сетевые проверки. Р3 делает BO-01/benign plans, job/lease protocol, HTTP execution и step results. Р4 соединяет control states, detector view, правило, evaluator и минимальный UI/report.

Общие контракты имеют owner и совместное ревью: API — Р1+Р2; policy/event — Р2+Р4; job/steps — Р3+Р4; oracle — Р1+Р3+Р4. Р4 не должен самостоятельно реализовывать все интеграционные адаптеры: fixture/oracle data предоставляет Р1, admission/manifests — Р2, worker protocol — Р3.

## Проверки реализации

| Область | Обязательная проверка |
|---|---|
| API/schema | Один spec для обоих режимов; positive examples, unknown/duplicate fields, types/bounds/nullability/query, body forwarding |
| JWT/BOLA | Неверные alg/signature/iss/aud/exp/nbf; valid token многократен; чужие profile/order и отсутствующие дают 404, свои доступны |
| Replay | Same key/body, altered body, spent intent/new key, параллельные key, expiry, cart revision, retry после потери ответа/commit |
| Бизнес-транзакция | Реальные cart/coupon races, rollback на критичных шагах, один эффект, expiry equality, серверная цена и независимый пересчёт |
| Injection | Реальный canary leakage vulnerable, отсутствие leakage protected, literal punctuation/search и sort allowlist |
| Audit | Atomic mutation/outbox, audit отказа, duplicate delivery, id conflict, oversized event, durable receipt после crash |
| Detector | Runtime SQL grants, YAML whitelist, поздний commit меньшего id, atomic state/incident/marker, out-of-order/ties, CLOSE/retraction/restart |
| Orchestration | Client timeout при продолжающемся SQL, stale lease/epoch, missing terminal slot, запрет reset до quiescence |
| Метрики/UI | N/A при нуле, incomplete не FN, wrong class FP, несколько incidents на episode, benign retry, escaping/CSRF и отсутствие секретов |
| Runtime | Запрещённый egress/host/direct backend/management, scoped credentials, recovery detector/control, measured startup/resource limits |

SQL locks/grants/concurrency проверяются Testcontainers с PostgreSQL, не H2. Unit tests подходят parser/policies и чистым функциям. Независимая state model для add/change/coupon/intent/checkout/retry проверяется jqwik; production calculator не используется как expected model. Конкурентные interleavings дополнительно задаются barriers.

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
| FR-23–FR-28 | Schema и неизвестные поля; owner-check; source/account limits/backoff; SQL-параметризация; серверная цена/idempotency/atomic coupon; durable allow/block/flag с причиной |
| FR-29 | Отдельные modes политик для нового run; обязательные эксплуатационные проверки не отключаются |
| FR-30–FR-35 | Общий event; независимый detector незаблокированных попыток; UI/filters/card/steps/reasons; TP/FP/FN; сравнение эффектов/нарушений/детекции/FP; JSON-export |
| FR-36–FR-39 | Benign workload для FP; централизованные structured logs; trace runner→gateway→shop→detector; redaction секретов |

## Нефункциональная приёмка

| Код | Условие готовности |
|---|---|
| NFR-1 | Реальными запросами из runner подтверждён запрет внешних целей и обхода gateway |
| NFR-2 | Одна Compose-команда на чистой описанной машине; cold readiness ≤10 минут, downloads учтены, warm time отдельно |
| NFR-3 | Только synthetic data, локальная генерация keys, safe env example и отсутствие реальных секретов |
| NFR-5 | Отказ detector/UI не останавливает API при доступном audit; восстановление дочитывает evidence |
| NFR-6 | Java modules, typed contracts и versioned config, а не несвязанные скрипты |
| NFR-7 | Автоматические проверки одной командой и в CI, сохранённые reports |
| NFR-8 | README, архитектура/схемы, решения и tradeoffs, API/сценарии/метрики, описание фактического вклада |
| NFR-9 | Из UI доступны создание/start/cancel, сравнение, карточки и export; отдельная дизайн-проработка не требуется |

## Демонстрация и сдача

Поднять стенд и показать health/версии/роли; выполнить BO-01 на двух режимах; показать response, owner oracle, incident и decision; продемонстрировать checkout retry/race по числу эффектов; выполнить пять семейств с benign traffic и открыть сравнение со знаменателями. Затем остановить detector, показать продолжение API и последующее дочитывание; для потерянного evidence показать INCONCLUSIVE. Завершить JSON-export и traces с объяснением остаточных рисков.

Результат сдачи: исходный код в публичном репозитории, воспроизводимые команды, актуальные схемы и документация, тестовые отчёты, видео либо сценарий демонстрации и фактический вклад каждого участника. До выполнения проверок проектная спецификация не считается доказательством готового стенда.
