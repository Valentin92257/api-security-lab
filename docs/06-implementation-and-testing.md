# Реализация и приёмка

Сейчас готовы каркас четырёх приложений, Docker Compose/health-checks, [контракты](../contracts/README.md) и генерация HTTP-интерфейсов/DTO. Ниже описан план реализации и приёмки, а не уже пройденные проверки.

## Порядок разработки

| Этап | Работа | Критерий выхода |
|---|---|---|
| 0. Контракты | Wire schemas, API/errors, версии зависимостей; spikes validator, локальный limiter, SQL grants | Примеры валидны, зависимости собираются, используемые OpenAPI keywords проверены |
| 1. Skeleton | Maven modules, Compose, БД и роли, keys, health, logs/traces, служебные interfaces | Запрос через gateway; HTTP-клиент генератора допускает только gateway, внешний egress control закрыт, detector не читает labels |
| 2. Вертикальный BOLA | Fixture/users/orders, BO-01 и benign, access-log writer и доставка Detector, одно правило, oracle, UI/report | Доказана утечка vulnerable, protected сохраняет owner; есть обнаружение при 200 и JSON-отчёт |
| 3. API и защита | Полный API, catalog canary, checkout/idempotency/coupon, schema/rate policies | Общие benign contract tests двух режимов; согласованные фикстуры и атомарный checkout |
| 4. Все угрозы | CS/BO/RP/IJ/BL со всеми subcases, benign episodes, windows/incidents | Для каждого случая есть effect criterion, oracle, evidence и результат |
| 5. Достоверность | CLOSED/lastSequence/finishAnalysis, in-flight, log gaps/duplicates, timeout при живом сервере, безопасный reset | Потеря доказательств не даёт достоверный зелёный отчёт; сбой делает run INCONCLUSIVE без продолжения |
| 6. Сдача | Paired series/seeds, performance/cold start, export, runbook, схемы, вклад | Воспроизводимый запуск, regression reports, демонстрация и документация реализации |

Порядок отражает зависимости, календарный план уточняется после первого сквозного результата. Исследования OpenFGA, Schemathesis или PyOD начинаются после обязательного набора и не блокируют его.

## Первая интеграция четырёх участников

Р1 делает собственный/чужой order, данные Fixture, две стратегии owner-check, access-log writer и readonly данные для oracle. Р2 делает gateway-прокси с двумя listener, JWT/защитные валидаторы внутри Shop совместно с Р1, единые errors, access-log writer и сетевые проверки. Р3 делает BO-01/benign plans, подготовку, запуск и отмену сценариев, HTTP execution и сохранение результатов. Р4 соединяет control states, приём логов Detector, правило, evaluator и минимальный UI/report.

Общие контракты имеют owner и совместное ревью: API - Р1+Р2; policy/access-log - Р2+Р4; plan/steps - Р3+Р4; oracle - Р1+Р3+Р4. Р4 не должен самостоятельно реализовывать все интеграционные адаптеры: fixture/oracle data предоставляет Р1, Runtime/lastSequence - Р2, модуль исполнения и cancellation - Р3.

## Проверки реализации

| Область | Обязательная проверка |
|---|---|
| API/schema | Один spec для обоих режимов; positive examples, unknown/duplicate fields, types/bounds/nullability/query, body forwarding |
| Gateway | Передача метода/path/query/body/Authorization/Idempotency-Key/requestId и ответа; отсутствие JWT/schema/rate enforcement в proxy; 502/504; отсутствие автоматических retries; user=null в логе |
| JWT/BOLA | Неверные alg/signature/iss/aud/exp/nbf; valid token многократен; чужие profile/order и отсутствующие дают 404, свои доступны |
| Login limits | Параллельный consume/backoff; ограничение только в protected Shop и run isolation; безопасный TTL; overflow 503 + INCONCLUSIVE; старый callback не меняет новый run; restart не продолжает опыт |
| Replay | Same key/body, altered body, оформленная cart revision/new key, параллельные key, expiry купона, cart revision, retry после потери ответа/commit |
| Бизнес-транзакция | Реальные cart/coupon races, rollback на критичных шагах, один эффект, expiry equality, серверная цена и независимый пересчёт |
| Injection | Реальный canary leakage vulnerable, отсутствие leakage protected, literal punctuation/search и фиксированный порядок по id |
| Security logs | AccessLog/schema/redaction; один реальный status после commit/rollback/ошибки; sequence 1..N; identical duplicate/conflict; oversized record; отсутствие sampling/drop |
| Detector | Изоляция от Shop/Control, YAML whitelist AccessLog, out-of-order/ties, отсутствие двойного вклада; FINISHING в границах N, COMPLETED/FAILED только identical retries; конфликт отклоняет всю пачку |
| Orchestration | Client timeout при продолжающемся SQL, deadline/cancellation и смена instanceId, missing sequence/attempt, finish ниже принятого sequence → 409, запрет reset до quiescence |
| Исполнение/UI | Один активный trial; UI/cancel доступны при максимальном бюджете runner; отдельные executors; bounded results; остановка без новых retry после cancel/deadline |
| Метрики/UI | N/A при нуле, incomplete не FN, ошибка family не меняет TP; detection согласован с IncidentAssessment и всеми cases episode, несколько incidents на episode, benign retry, escaping/явный Operator Bearer и отсутствие секретов |
| Runtime | Shop отклоняет mode, не совпадающий с профилем; mode не переключает реализацию |
| Изоляция | Внешний egress control закрыт; HTTP-клиент генератора отвергает host/direct backend/management; ArchUnit проверяет зависимости модулей; scoped credentials, INCONCLUSIVE без resume после restart detector/control, measured startup/resource limits |

SQL locks/grants/concurrency проверяются Testcontainers с PostgreSQL, не H2. Unit tests подходят parser/policies и чистым функциям. Независимая state model для setQuantity/coupon/checkout/retry проверяется jqwik; production calculator не используется как expected model. Конкурентные interleavings дополнительно задаются barriers.

Планируемый pipeline внутри Maven-контейнера `mvn verify`: compile → unit/schema/contract → PostgreSQL integration → компактный security smoke. Полная приёмочная серия - `mvn -Pacceptance verify` с фиксированными seed/budget/config и сохранёнными reports. Это план будущего pipeline; текущие команды генерации и компиляции приведены в [README](../README.md#генерация-кода-из-openapi). CI проверяет воспроизводимость генерации; после появления реализации pipeline должен соответствовать её фактическим Maven profiles.

Regression падает при нарушении обещанного protected-инварианта, неподтверждённом vulnerable baseline, несовместимых snapshots, потере обязательного evidence, нарушении изоляции или утечке секрета. Гонки проверяются серией; отсутствие воспроизведения в серии не выдаётся за успешную демонстрацию. CS-02 и другие объявленные остаточные риски оцениваются по их реальной гарантии.

## Покрытие функциональных требований

Коды служат идентификаторами приёмки; содержание требований дано здесь полностью в пределах указанных групп. Текущий контракт использует фиксированные vulnerable/protected; индивидуальное переключение политик FR-29 не входит в MVP.

| Требования | Проверяемый результат |
|---|---|
| FR-1-FR-3 (сокращённый scope) | Атомарная регистрация пользователя/корзины, login/JWT и профиль по ID; исходных пользователей готовит fixture |
| FR-4-FR-9 (сокращённый scope) | Поиск каталога, GET cart, PUT количества/купона, checkout и заказ по ID; карточка товара, история и удаления исключены; один контракт двух режимов |
| FR-10-FR-16 | Активы/угрозы/меры/остаточные риски; credential stuffing, BOLA profile/order, replay, injection, четыре business subcases; отдельный критерий эффекта и инвариант |
| FR-17-FR-20 | Создание trial с families/seed; автоматические vulnerable/protected runs; подготовка/шаги/результаты; безопасный reset модулем Control |
| FR-21-FR-22 | Обязательный gateway-прокси; JWT claims/signature внутри Shop и одноразовость бизнес-операции при многократном access JWT |
| FR-23-FR-28 | Schema и неизвестные поля; owner-check; source/account limits/backoff; SQL-параметризация; серверная цена/idempotency/atomic coupon; итоговые access-логи; причины решений - во внутренней диагностике |
| FR-29 | Два фиксированных приложения: vulnerable с учебными слабостями и protected со всеми защитами; Gateway одинаковый нейтральный прокси |
| FR-30-FR-35 | AccessLog; независимый Detector и итоговые incidents; UI/progress/cases/reasons; TP/FP/FN; сравнение эффектов/детекции/FP; JSON-отчёт |
| FR-36-FR-39 | Benign workload для FP; централизованные structured logs; trace runner→gateway→shop→detector; redaction секретов |

## Нефункциональная приёмка

| Код | Условие готовности |
|---|---|
| NFR-1 | Внешний egress control закрыт сетевыми тестами; сценарный HTTP-клиент отвергает внутренние цели кроме gateway. Внутри общей JVM нет отдельной сетевой изоляции runner |
| NFR-2 | Одна Compose-команда на чистой описанной машине; cold readiness ≤10 минут, downloads учтены, warm time отдельно |
| NFR-3 | Только synthetic data, локальная генерация keys, safe env example и отсутствие реальных секретов |
| NFR-5 | API отправляет access-логи напрямую Detector без участия Control; отставание обработки допустимо до deadline. Restart/отказ процесса делает run INCONCLUSIVE, восстановления run нет |
| NFR-6 | Java modules, typed contracts и versioned config, а не несвязанные скрипты |
| NFR-7 | Автоматические проверки одной командой и в CI, сохранённые reports |
| NFR-8 | README, архитектура/схемы, решения и tradeoffs, API/сценарии/метрики, описание фактического вклада |
| NFR-9 | Из UI доступны создание/start/cancel, сравнение, карточки и export; отдельная дизайн-проработка не требуется |

## Демонстрация и сдача

Поднять стенд и показать health/версии/роли; выполнить BO-01 на двух режимах; показать response, owner oracle, incident и decision; продемонстрировать checkout retry/race по числу эффектов; выполнить пять семейств с benign traffic и открыть сравнение со знаменателями. Затем показать отставание обработки при работающем detector и корректное ожидание FINALIZING; для пропущенного sequence или перезапуска процесса показать INCONCLUSIVE без возобновления run. Завершить JSON-export и traces с объяснением остаточных рисков.

Результат сдачи: исходный код в публичном репозитории, воспроизводимые команды, актуальные схемы и документация, тестовые отчёты, видео либо сценарий демонстрации и фактический вклад каждого участника. До выполнения проверок проектная спецификация не считается доказательством готового стенда.

Проверки упрощённого checkout: новый key для оформленной revision возвращает CONFLICT; устаревшая неоформленная revision - CONFLICT. Успешный retry после очистки корзины и истечения купона возвращает прежний Order. Два разных key одной revision создают один заказ; vulnerable replay той же версии создаёт повторный эффект. Изменение замороженного каталога/условий купона приводит к INCONCLUSIVE, а не к корректному результату ASR.

Минимальный контракт магазина содержит 9 операций. Проверить создание позиции через PUT, замену количества без инкремента и no-op без повышения revision; protected отклоняет quantity=0, vulnerable допускает учебный BL-02; общая генерация Shop API не запускает Bean Validation до выбора режима. Сценарии получают ID своих/чужих ресурсов из приватного manifest, подтверждают login чтением профиля по ID и не требуют регистрации или истории заказов. Исключённые маршруты дают 404, а неподдерживаемый метод существующего маршрута - 405.

Регистрация: проверить 201 User и пустую Cart с revision=0, отсутствие password/hash в ответе и логах, вход созданного пользователя, 400 для невалидных полей и 409 CONFLICT после нормализации. Гонка одинаковых регистраций создаёт ровно одного пользователя и одну корзину; ошибка создания корзины откатывает пользователя. Проверки одинаковы для vulnerable и protected.

Spring Boot подключает Bean Validation provider; у Shop API автоматический @Valid отключён для учебных режимов, Runtime/Control/Detector сохраняют валидацию.

## План и границы SECURITYLAB-13

1. Прокси protected: фиксированный listener 8082, конфигурируемый upstream, сохранение HTTP, отключение retries/redirects, конечные таймауты и Problem 502/504.
2. Корреляция: обязательный X-Request-Id; единый trace_id в MDC, итоговый JSON-лог в Gateway и обоих Shop. Для некорректного ID — 400 и один новый ID отказа. Без бизнес-политик и production-заглушек.
3. Интеграция и проверки: Compose/environment, health обоих портов, тестовый upstream и HTTP-тесты Shop, smoke на запущенных контейнерах. Тесты и smoke подготовлены; выполнение Docker-сборки и совместной проверки ещё требуется.

Минимальный лог каркаса содержит service/trace_id/method/path/status/duration_ms, без query, тел и credentials. Это диагностическая корреляция, не готовый контракт Detector: sequence/runId/user/req_hash и LogBatch будут отдельной задачей. Runtime admission и vulnerable-маршрут также ещё не реализованы. Команды и настройки приведены в корневом README.
