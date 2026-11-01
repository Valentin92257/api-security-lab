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

Миграции Control находятся в [`db/control/migrations`](../../db/control/migrations).
Flyway создаёт схему `control` и применяет версионированные SQL с таблицами, индексами
и правами. `V1__trials.sql` создаёт trial с ключом идемпотентности и единственный слот.
`V2__runtime_permissions.sql` задаёт права runtime-роли на подключение и схему.
Следующие изменения добавляются как V3, V4 и далее; применённые файлы не переписываются.
Это внутренняя модель Control в БД `lab_control`; Detector её не использует.
