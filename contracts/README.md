# Контракты API Security Lab

| Интерфейс | Спецификация |
|---|---|
| Генератор → Gateway → Shop | [Существующий Shop API](shop-api.openapi.yaml) |
| Control / Shop / Gateway → Detector | [Detector API](detector-api.openapi.yaml) |
| Control → Shop/Gateway | [Runtime API](runtime-api.openapi.yaml) |
| Дашборд → Control | [Control API](control-api.openapi.yaml) |
| Внутренние модули Control | [Входы и выходы](modules.md) |

Общие типы: [schemas/](schemas/). [Правила взаимодействия](semantics.md), [примеры](examples/).

Control запускает оба режима и проверяет успех атак. Shop/Gateway передают логи Detector. Detector возвращает только итоговые инциденты; Control оценивает их верность. Сетевые интерфейсы - HTTP/JSON + OpenAPI 3.1.
