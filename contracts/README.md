# Контракты API Security Lab

Единый актуальный комплект контрактов. При расхождениях с исходным дизайном в `docs/` используются эти спецификации.

| Интерфейс | Спецификация |
|---|---|
| Генератор → Gateway → Shop | [Shop API](shop-api.openapi.yaml) |
| Control / Shop / Gateway → Detector | [Detector API](detector-api.openapi.yaml) |
| Control → Shop/Gateway | [Runtime API](runtime-api.openapi.yaml) |
| Дашборд → Control | [Control API](control-api.openapi.yaml) |

- Gateway не выполняет защитные политики бизнес-трафика: JWT, schema validation и login limits находятся в Shop. JWT обязателен в обоих Shop; protected применяет все защитные проверки, vulnerable допускает документированные учебные нарушения и не применяет login limiter. Gateway пишет user=null и передаёт Authorization в Shop; Runtime admission и ServiceBearer остаются инфраструктурой.
- Control запускает выбранные семейства атак сначала в vulnerable, затем в protected с одинаковыми seed и исходными данными. Одновременно активно одно испытание; сброс БД допустим только после безопасного завершения предыдущего.
- Генератор и подготовка БД - модули Control. Служебный Runtime API задаёт активный runId; бизнес-запросы передают X-Request-Id и IP виртуального клиента в доверенном X-Forwarded-For.
- Shop/Gateway передают Detector по HTTP/JSON одну итоговую access-запись на запрос у каждого producer. [Формат](schemas/logs.yaml), [пример](examples/log-batch.json). Detector хранит логи и возвращает только итоговые инциденты; family необязателен.
- Control проверяет эффект атаки по ответам и БД, сопоставляет инциденты с эталоном и формирует общий отчёт двух режимов. Счётчики и метрики дашборд вычисляет из отчёта по [общим правилам](semantics.md).

## API дашборда

Базовый путь `/control/v1`, отдельный operator Bearer.

| Метод | Результат |
|---|---|
| `GET /scenarios` | Пять доступных семейств атак |
| `POST /trials` | Создать и сразу запустить испытание: families и необязательный seed |
| `GET /trials` | Список испытаний с пагинацией |
| `GET /trials/{trialId}` | Состояние, общий прогресс и cleanupSafe |
| `POST /trials/{trialId}/cancel` | Остановить новые шаги и завершить активные операции |
| `GET /trials/{trialId}/report` | Общий отчёт, включая инциденты и оценку Control; до готовности 409 |

POST запуска требует Idempotency-Key: тот же ключ и body возвращают прежнее испытание, другой body - 409. Если seed не передан, Control выбирает его и возвращает в trial. Отмена и ошибки дают частичный отчёт; новое испытание не начинается, пока cleanupSafe=false.

Исходный `docs/data/lab-openapi.yaml` перенесён и сокращён до [Control API](control-api.openapi.yaml). Каталог свёлся к `/scenarios`, создание и start эксперимента объединены в POST `/trials`, общий прогресс включён в trial, результаты runs - в report, отчёты и comparison объединены в один report. Параметры trial после создания неизменяемы; вместо CAS-команд используются Idempotency-Key при запуске и идемпотентная отмена. Отдельные HTTP-методы просмотра событий и редактирования инцидентов не входят в текущий контракт. SQL jobs и доставка логов через Control заменены Detector API.

## Модели

- Control: запрос запуска, состояние испытания, общий отчёт, результат одного режима, результат проверки и оценка инцидента.
- Detector: состояние анализа, границы логов для завершения и инцидент.
- Runtime: настройки прогона и состояние процесса.
- Логи: одна access-запись и пачка. Успешный приём - 204 без отдельного DTO.
- Страницы Control/Detector: только items и total.

Общие типы: [schemas/](schemas/). [Правила взаимодействия](semantics.md), [примеры](examples/). [Генерация Java, модули и пути кода](../README.md#генерация-кода-из-openapi).

Каталог Shop и публичные ошибки описаны в [API и модели данных](../docs/02-api-and-data.md).
