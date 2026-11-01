# Данные и схемы

Действующие черновики физической модели магазина:

| Файл | Назначение |
|---|---|
| [shop-schema.sql](shop-schema.sql) | Семь общих таблиц обеих shop DB |
| [shop-protected.sql](shop-protected.sql) | Дополнительные ограничения protected |
| [shop-invariants.sql](shop-invariants.sql) | Readonly независимые проверки evaluator |
| [shop.dbml](shop.dbml), [shop.mmd](shop.mmd), [er.html](er.html) | Модели и ER магазина; прежние lab-фрагменты ER помечены как архив |

Порядок применения SQL и бизнес-транзакции: [API и данные](../02-api-and-data.md). Данные готовит Fixture-модуль Control напрямую в БД при закрытых runtimes. HTTP-спецификации находятся только в [contracts](../../contracts/README.md).

Физическое хранение Control/Detector не задаётся OpenAPI. [legacy-lab](legacy-lab/README.md) - архив отменённого SQL-протокола, не текущие миграции.

Flyway создаёт схему `control` в БД `lab_control` и применяет [миграции](../../db/control/migrations).
`V1__trials.sql` создаёт таблицу `trials` с ключами идемпотентности и слот исполнения,
`V2__runtime_permissions.sql` задаёт runtime-роли права подключения и доступа к схеме.
Применённые файлы не редактируются; следующие изменения - V3, V4 и далее.
Detector эту БД не использует.
