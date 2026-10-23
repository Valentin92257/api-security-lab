# Модули внутри Control

Вызовы в одном процессе; типы - [schemas/modules.yaml](schemas/modules.yaml).

| Порт | Вход | Выход |
|---|---|---|
| Fixture.prepare | mode, seed, businessTimeBase | FixtureSnapshot, доступ к credentials в памяти |
| Runner.prepare | runId, mode, families, seed, snapshot, deadlineAt | Подготовленный объект и RunPlan |
| Runner.start | Подготовленный объект, HTTP-клиент, credentials, AttemptSink | Асинхронный результат ExecutionSummary |
| Runner.cancel | Активный запуск | Остановка новых шагов и retries |
| AttemptSink.append | Пачка AttemptResult | Подтверждение сохранения; повторы requestId/content безопасны |
| Evaluator.evaluate | План, исходный snapshot, attempts, readonly Shop, итоговые incidents | RunReport |

Runner фиксирует акторов, ресурсы и ATTACK/BENIGN до запуска. Completion наступает после сохранения всех результатов. Пропущенные шаги - INCONCLUSIVE. Evaluator независимо проверяет эффект по ответам и БД; правила cases описаны в [каталоге проекта](../docs/05-scenarios-and-experiments.md). Runner не получает доступ к БД; пароли/JWT доступны только через credentials в памяти и не сохраняются в результатах.
