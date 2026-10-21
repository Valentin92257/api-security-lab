# API и данные

## Общие правила

Один нормативный контракт описывает vulnerable и protected. Protected выполняет все правила; vulnerable намеренно отключает перечисленные ниже проверки для учебных сценариев. Базовый путь — `/api/v1`, внутренние origins — `http://lab-gateway:8081` и `http://lab-gateway:8082`. Версия машинного контракта — OpenAPI 3.1.0.

JSON UTF-8, camelCase, даты UTC RFC 3339. Все поля описанных request/response objects обязательны, кроме явно отмеченных optional; null разрешён только явно. Request objects закрыты: неизвестные/повторяющиеся JSON-поля и query parameters, дубли query names, несоответствие типов — 400. Преобразование строки в число не выполняется. Для операций с телом требуется `Content-Type: application/json`; для GET/DELETE тело не предусмотрено. Ответы JSON, ошибки `application/problem+json`.

| Тип | Определение |
|---|---|
| `Id` | Целое `1…9007199254740991`; Java long, SQL bigint |
| `Revision` | Целое `0…9007199254740991`, монотонная версия корзины |
| `Money` | Целое `0…9900000000000`, рубли; Java long/SQL bigint; currency всегда RUB |
| `Quantity` | Целое 1…99; максимум 100 различных позиций корзины |
| `Time` | Строка RFC 3339 UTC |
| `Uuid` | Строка UUID |
| `Page<T>` | `{items:T[], limit:integer, offset:integer, total:integer}`; total — число доступных объектов до пагинации |

Цена товара — `1…1000000000`. Дробные рубли не поддерживаются. Все денежные вычисления целочисленные с проверкой переполнения: subtotal = сумма quantity × unitPrice; discount = floor(subtotal × percent / 100); total = subtotal − discount. Без купона discount=0.

## Аутентификация и заголовки

`Authorization: Bearer <accessToken>` обязателен, кроме register/login. JWT подписан локальным RS256-ключом target; проверяются фиксированный alg, signature, iss, aud, exp и nbf. Claims: sub=userId строкой, iat/nbf/exp, уникальный jti. TTL по умолчанию 15 минут, clock skew 5 секунд; время реальное. Ключ/issuer/audience выбираются по listener, token другого target отклоняется. Shop также проверяет JWT. Повторное использование действительного access JWT разрешено.

Gateway создаёт `X-Request-Id` для каждого HTTP-attempt и возвращает его в ответе; trace context распространяется дальше. Пользовательские role/owner/target headers полномочий не дают. Чужой и отсутствующий profile/order возвращают одинаковый 404.

Для `POST /orders` обязателен `Idempotency-Key`: случайная строка 16…128 символов `[A-Za-z0-9._-]`. Успешный ответ содержит `Idempotency-Replayed: false` при первом выполнении и `true` при возврате сохранённого результата. Для остальных операций этот заголовок не предоставляет гарантию идемпотентности.

## Запросы и операции

Поля тел обязательны; пустые тела не подменяются значениями по умолчанию. Во всех строках, кроме двух auth-операций, требуется JWT.

| Метод и путь | Тело / параметры | Ответ и семантика |
|---|---|---|
| `POST /auth/register` | `{login:string, password:string, name:string, email:string}` | 201 User; пользователь и пустая корзина создаются одной транзакцией |
| `POST /auth/login` | `{login:string, password:string}` | 200 LoginResult; неверная пара — 401 INVALID_CREDENTIALS |
| `GET /users/me` | Нет | 200 User текущего subject |
| `GET /users/{userId}` | userId: Id | 200 собственный User; иначе 404 |
| `GET /products` | q, sort, limit, offset — optional | 200 Page<Product>, только опубликованные товары |
| `GET /products/{productId}` | productId: Id | 200 Product; отсутствующий/draft — 404 |
| `GET /cart` | Нет | 200 Cart текущего пользователя |
| `POST /cart/items` | `{productId:Id, quantity:Quantity}` | 200 Cart; количество прибавляется к имеющемуся, новый товар создаёт позицию |
| `PATCH /cart/items/{productId}` | `{quantity:Quantity}` | 200 Cart; абсолютное количество; отсутствующая позиция — 404 |
| `DELETE /cart/items/{productId}` | Нет | 200 Cart; отсутствие позиции допустимо |
| `PUT /cart/coupon` | `{code:string}` | 200 Cart; выбирает/заменяет единственный купон, использование не расходует |
| `DELETE /cart/coupon` | Нет | 200 Cart; отсутствие купона допустимо |
| `POST /checkout-intents` | `{cartRevision:Revision}` | 201 CheckoutIntent; фиксирует серверный расчёт непустой актуальной корзины |
| `POST /orders` | `{intentId:Uuid, quotedTotal:Money}` + Idempotency-Key | 201 Order; успешный retry возвращает тот же status/body |
| `GET /orders` | limit, offset — optional | 200 Page<OrderSummary>, собственная история |
| `GET /orders/{orderId}` | orderId: Id | 200 собственный Order; иначе 404 |

Login: 3…32 ASCII-символа `[a-z0-9_]+` после trim/lowercase; одинаковая нормализация регистрации, поиска и rate key. Пароль: 8…72 UTF-8 bytes, без trim/нормализации, никогда не возвращается. Name: 1…100 символов после trim. Email: синтаксически корректный адрес до 254 символов, для стенда trim/lowercase всего адреса и unique normalized value. Coupon code: 1…32 символа `[A-Z0-9_-]` после trim/uppercase; неизвестный код — 404.

Поиск q — строка 0…120 символов, default пустая; регистронезависимое буквальное вхождение в name или description, `%`/`_` не являются пользовательскими wildcard. Sort — `id` (default), `name`, `price`, по возрастанию, затем id; имена SQL-полей выбираются по enum. Limit 1…100, default 20; offset 0…10000, default 0. История заказов сортируется createdAt DESC, id DESC. Invalid id/enum/range — 400; неизвестный маршрут — 404; неподдерживаемый метод известного маршрута — 405 с Allow.

## Объекты ответа

| Объект | Полный состав полей |
|---|---|
| `User` | id:Id, login:string, name:string, email:string |
| `LoginResult` | accessToken:string, tokenType:"Bearer", expiresIn:integer — оставшийся срок в секундах |
| `Product` | id:Id, name:string(1…120), description:string(0…4000), price:Money > 0, currency:"RUB" |
| `LineItem` | productId:Id, name:string, quantity:Quantity, unitPrice:Money > 0, lineTotal:Money |
| `CartCoupon` | code:string, discountPercent:integer(1…100), eligibility:"VALID"/"EXPIRED"/"EXHAUSTED" |
| `OrderCoupon` | code:string, discountPercent:integer(1…100); snapshot условий |
| `Cart` | id:Id, userId:Id, revision:Revision, items:LineItem[], coupon:CartCoupon/null, subtotal:Money, discount:Money, total:Money, currency:"RUB", updatedAt:Time |
| `CheckoutIntent` | id:Uuid, cartRevision:Revision, items:LineItem[] непустой, coupon:OrderCoupon/null, subtotal:Money, discount:Money, total:Money, currency:"RUB", expiresAt:Time |
| `Order` | id:Id, userId:Id, intentId:Uuid, status:"CREATED", items:LineItem[] непустой, coupon:OrderCoupon/null, subtotal:Money, discount:Money, total:Money, currency:"RUB", createdAt:Time |
| `OrderSummary` | Все поля Order, кроме items |

В Cart цены текущие; в intent/order — snapshot. LineTotal всегда quantity × unitPrice. Пустая корзина имеет items=[], subtotal=discount=total=0. CartCoupon выбирается без резерва: PUT проверяет доступность сейчас, checkout проверяет повторно. Если купон стал недоступен, GET cart сохраняет code и percent, показывает соответствующий eligibility и discount=0. Intent для недоступного купона не создаётся.

Корзина принадлежит JWT subject; ownerId/userId не принимаются в её mutation requests. Draft/отсутствующий товар нельзя добавить. Превышение 99 в существующей позиции или 100 разных товаров — 400 CART_LIMIT_EXCEEDED. Изменение состава/quantity/купона увеличивает revision; no-op PATCH/DELETE/повтор выбора того же купона её не меняет. Отмен заказов, платежей и возвратов в версии 1 нет. Hash пароля, flags published/canary и внутренние fingerprints в публичные ответы не входят.

## Checkout и повторы

Клиент получает Cart, создаёт intent с её revision и отправляет `POST /orders` с intentId, total из intent как quotedTotal и новым key. Intent хранит owner, cart/revision, позиции, версии цен/купона, fingerprint и expiry. TTL по умолчанию 120 секунд по бизнес-часам стенда. Корзина не резервируется; любое её реальное изменение делает intent неактуальным.

Key действует в scope `(authenticated user, method, route, key)`. Fingerprint — SHA-256 версии контракта, маршрута и канонического разобранного тела: одинаковые типизированные значения при разном порядке полей дают один fingerprint. JWT/requestId/trace не включаются. Успешный заказ сохраняет idempotency_key_hash, request_fingerprint и response_body; статус успешного ответа всегда 201. Неуспешная транзакция не оставляет заказ или отдельную запись key.

| Условие | Результат protected |
|---|---|
| Тот же key и fingerprint после успеха | Прежние 201/Order без нового эффекта, даже если intent уже истёк |
| Тот же key, другое тело | 409 IDEMPOTENCY_CONFLICT |
| Новый key, использованный intent | 409 INTENT_ALREADY_USED |
| Параллельный тот же key | Ожидание первого commit; прежний успех либо 409 OPERATION_IN_PROGRESS после ограниченного ожидания |
| Два intent одной cart revision | Один order; второй 409 CART_CHANGED |
| Отсутствующий/чужой intent | 404 NOT_FOUND |
| Истёкший неиспользованный intent | 409 INTENT_EXPIRED |
| Изменение корзины / пустая корзина при создании intent | 409 CART_CHANGED / CART_EMPTY |
| Изменение цены или неверный quotedTotal | 409 PRICE_CHANGED |
| Изменение условий купона / expiry / исчерпание | 409 COUPON_CHANGED / COUPON_EXPIRED / COUPON_EXHAUSTED |

Lookup успешного key выполняется после auth/schema validation, но до повторной проверки intent. После timeout клиент повторяет тот же key/body. Неуспешные результаты не кешируются; гарантия распространяется на успешный бизнес-эффект. Keys и consumed intents сохраняются до завершения жизненного цикла фикстуры и отчёта. Автоматический retry инкрементального `POST /cart/items` не допускается; PATCH абсолютного количества и DELETE не создают повторного изменения при том же состоянии.

## Ошибки

Problem содержит только type:string URI, title:string, status:integer, code:string, requestId:Uuid. SQL, stack trace, чужой owner и секреты не возвращаются.

```json
{"type":"urn:lab:problem:cart-changed","title":"Корзина изменилась","status":409,"code":"CART_CHANGED","requestId":"e42fb0db-45dc-4b1a-8157-66c3a815e450"}
```

| Status | Code и условие |
|---|---|
| 400 | VALIDATION_FAILED — JSON/types/bounds/обязательные поля/query; CART_LIMIT_EXCEEDED — результирующий размер корзины |
| 401 | AUTH_REQUIRED — отсутствующий/недействительный JWT; INVALID_CREDENTIALS — login failure; Bearer challenge в WWW-Authenticate |
| 404 | NOT_FOUND — неизвестный или недоступный ресурс/маршрут |
| 405 | METHOD_NOT_ALLOWED |
| 409 | LOGIN_TAKEN, EMAIL_TAKEN; конфликты checkout из таблицы выше |
| 413 / 415 | PAYLOAD_TOO_LARGE / UNSUPPORTED_MEDIA_TYPE |
| 429 | RATE_LIMITED; Retry-After в секундах |
| 503 | DEPENDENCY_UNAVAILABLE — инфраструктура/transaction timeout; RUN_CLOSED — закрытый admission |
| 500 | INTERNAL_ERROR — неожиданная ошибка, без внутренних деталей |

Общие HTTP-ошибки применимы ко всем операциям. Business conflict не меняет состояние; 500/timeout не доказывает отсутствие commit. В measurement такие случаи проверяются по состоянию и полноте evidence.

## Схема данных

Две shop DB имеют одинаковые таблицы. Id/Revision/Money соответствуют bigint, Time — timestamptz, UUID — uuid. Все поля NOT NULL, кроме отмеченных `?`; FK ссылаются на id соответствующей сущности. Произвольное удаление каталога/пользователя с зависимыми orders не допускается.

| Таблица | Поля и ключи |
|---|---|
| `users` | id PK; login varchar(32) UNIQUE; name varchar(100); email varchar(254) UNIQUE; password_hash varchar(255) |
| `products` | id PK; name varchar(120); description text; price; published boolean; catalog_version bigint |
| `coupons` | id PK; code varchar(32) UNIQUE; discount_percent integer; expires_at; max_uses/used_count bigint; terms_version bigint |
| `carts` | id PK; user_id FK UNIQUE; coupon_id FK?; revision; updated_at |
| `cart_items` | cart_id FK, product_id FK, quantity integer; PK(cart_id, product_id) |
| `orders` | id PK; user_id/cart_id FK; cart_revision; intent_id FK; status CREATED; subtotal/discount/total; coupon_id FK?; coupon_code/percent/expires_at/terms_version snapshot?; created_at; idempotency_key_hash; request_fingerprint; response_body |
| `order_items` | order_id/product_id FK; quantity; name_snapshot; unit_price; catalog_version; PK(order_id, product_id) |
| `checkout_intents` | id UUID PK; user_id/cart_id FK; cart_revision; versioned snapshot JSONB; fingerprint; expires_at; consumed_order_id FK? |
| `security_outbox` | event_id UUID PK; producer_epoch UUID; payload JSONB; created_at; delivered_at?; retry_count; next_attempt_at |
| `execution_ledger` | request_id UUID PK; producer_epoch; state ACTIVE/COMPLETED/ABORTED; terminal_event_id?; started_at; finished_at? |

Snapshot intent содержит revision, items с ценами/catalog versions, coupon с условиями/версией и все суммы. Coupon snapshot заказа nullable целиком: без купона discount=0. Связь order→intent создаётся до установки consumed_order_id; успешный response_body хранится в самом заказе. Индексы: orders(user_id, created_at, id), cart_items по PK, outbox по недоставленным rows, ledger по epoch/state.

Protected constraints: quantity 1…99, положительная unit_price, неотрицательные суммы, discount≤subtotal, total=subtotal−discount, used_count от 0 до max_uses; UNIQUE(intent_id) и UNIQUE(cart_id, cart_revision) в orders. Общие PK/FK, уникальность пользователей и допустимые параметры каталога/купона сохраняются в обоих режимах. Защитные constraints устанавливаются только protected migration set. Межтабличная согласованность обеспечивается транзакциями и проверяется отдельно.

## Атомарное оформление

Все mutations корзины берут её row lock. При READ COMMITTED порядок protected checkout фиксирован:

1. Взять транзакционный advisory lock scope ключа, затем прочитать orders; для прежнего успеха сравнить fingerprint и вернуть сохранённый ответ.
2. Заблокировать cart, затем intent. Проверить owner, consumed state, revision и expiry.
3. Сверить snapshot с текущим составом, catalog/terms versions и quotedTotal. Бизнес-время должно быть строго меньше expiry: равенство уже означает истечение.
4. Для купона выполнить условное атомарное увеличение used_count при действующем сроке и остатке; ноль обновлённых строк означает конфликт.
5. Создать order/items, отметить intent consumed, очистить корзину и увеличить revision.
6. Проверить сохраненный в order response_body, обновить terminal execution ledger и добавить событие операции в outbox; commit всех изменений вместе.

Порядок locks: key → cart → intent → coupon, внутри одного типа — возрастающий id. Обратный захват не допускается. Ошибка до commit откатывает всё; ошибка ответа после commit разрешается retry того же key. Успешная бизнес-операция и её outbox атомарны; событие отказа после rollback пишется отдельной короткой audit-транзакцией. Redis не участвует в защите однократного эффекта.

Vulnerable использует именованные стратегии: пропуск owner-check; ограниченный SQL-каталог с конкатенацией; принятие недопустимого quantity/quotedTotal; неатомарный купон; повторное исполнение snapshot intent без consumed-check. Последнее воспроизводит replay после очистки корзины. JSON syntax/ресурсные пределы общие; автоматический `@Valid` не должен закрыть учебные слабости в обоих режимах. Catalog datasource имеет только SELECT products.

## Независимые проверки и фикстуры

| Свойство | Доказательство evaluator |
|---|---|
| Чужие profile/order не раскрыты | JWT subject сценария, owner из приватного manifest, фактически возвращённые поля |
| Допустимые количества, непустой order | Readonly cart/order items |
| Верные цены и скидки | Независимый расчёт по trusted catalog/terms snapshot соответствующей версии |
| Корректные суммы | Сумма строк и целочисленная формула discount/total |
| Купон | Время операции < expiry; used_count равен baseline + числу новых заказов с купоном и не превышает max_uses; один купон на order |
| Один эффект | Не более одного нового order на intent и cart revision |
| Видимость каталога | Draft canary отсутствует в нормативной выдаче |
| Доступ после login | Выданный token действительно открывает профиль; один status 200 недостаточен |

Evaluator не вызывает production calculator и не доверяет локальному признаку «проверка пройдена». Исторические цены/купоны сверяются с независимым snapshot, а не с текущим каталогом. Успех атакующего и нарушение гарантии различаются: первая корректная пара credentials может дать доступ при правильно работающем JWT.

Генератор fixture v1 создаёт 24 синтетических пользователя с корзинами, 12 опубликованных товаров и один draft canary. Пять купонных состояний: действующий, expired, exhausted, последнее доступное использование, 100% скидка. Начальная история обеспечивает собственные/чужие orders и согласованные счетчики купонов; каждому order назначены отдельные исторические intent/cart revisions. Хеши паролей формирует PasswordEncoder; пустые hashes запрещены.

Credential workload — 200 попыток с тремя корректными парами разных аккаунтов. Порядок, synthetic actors и значения воспроизводятся по seed; повторяемость password hash байт-в-байт не нужна. Manifest хранит version/hash, seed, businessTimeBase, ids/owners, начальные counts и snapshots, без паролей в экспорте. Перед RUNNING проверяется исходная согласованность. Catalog/terms не изменяются внутри прогона; бизнес-часы имеют одинаковую UTC-базу paired runs и монотонное продвижение, JWT использует реальные часы.

Подготовка/reset доступны только control через lab-admin, после завершения всех операций. Runtime shop не имеет TRUNCATE. Конечное состояние и evidence сохраняются до сброса.

## Миграции и жизненный цикл данных

Исполняемая исходная схема PostgreSQL 17 находится в `data/shop-schema.sql`; только для `shop_protected` после неё применяется `data/shop-protected.sql`. Обе базы получают `data/shop-invariants.sql`. Это начальные миграции пустых баз, не повторно запускаемые repair scripts. В Flyway их переносят в db/shop/common, db/shop/protected и db/lab с однозначными version numbers; один SQL-файл соответствует одной миграции, повторно примененные версии не редактируются. Их выполняет migration owner; runtime-приложения не получают DDL. SQL не создаёт и не наполняет фикстуры.

Порядок создания: все таблицы → PK/UNIQUE/CHECK → FK после появления обеих сторон → индексы → protected-only constraints → readonly функции oracle. Поэтому цикл `orders.intent_id` ↔ `checkout_intents.consumed_order_id` не требует отключения FK. При оформлении intent уже существует с consumed_order_id=NULL, затем вставляется order, после чего обновляется intent. При reset обе стороны очищаются административно в закрытой эпохе после сохранения доказательств; обычного API удаления истории нет.

Общие ограничения обеспечивают структурную целостность: типы, NOT NULL, идентификаторы, PK/FK, уникальность логинов/email/кодов, параметры каталога. Ограничения количества, итогов, счетчика купона и одноразового эффекта находятся только в protected migration. Общая схема не должна случайно устранять учебную уязвимость. Hash ключа, fingerprint и response_body входят в orders; UNIQUE(user_id,idempotency_key_hash) действует только в protected. Scope method/route не требует колонок: идемпотентность предусмотрена только для POST /api/v1/orders.

FK по умолчанию запрещают удаление родителя. CASCADE применяется только к составным частям `cart_items` и `order_items`; это не разрешение runtime удалять заказы. UNIQUE(carts.user_id) означает «не более одной корзины», а наличие корзины обеспечивает атомарная регистрация. Непустой заказ, максимум 100 разных позиций, совпадение owner и сумма всех строк — межстрочные условия: их проверяет транзакционный код и независимый oracle. Они не выдаются за обычные CHECK одной строки.

Индексы PK/UNIQUE создаёт PostgreSQL. Дополнительные индексы перечислены в словаре ниже; `outbox_pending_idx(next_attempt_at,event_id) WHERE delivered_at IS NULL` обслуживает dispatcher. `execution_ledger(producer_epoch,state)` обслуживает reconciliation. Для reverse lookup всех неуникальных FK предусмотрены индексы, где они нужны runtime-запросам; индексы не заменяют блокировок.

### Snapshot, время и идентификаторы

`snapshot_version=1` задаёт JSONB intent: `cartRevision`, `items:[{productId,name,quantity,unitPrice,catalogVersion}]`, `coupon:null|{id,code,discountPercent,expiresAt,termsVersion}`, `subtotal`, `discount`, `total`, `currency:"RUB"`. Поля обязательны, кроме отсутствующего купона, представленного целиком null. Fingerprint считается над каноническим JSON UTF-8, включающим версию; повторная сериализация не должна менять hash. Это snapshot расчёта shop, а не независимый эталон.

Объект `response_body` содержит полный нормативный Order response. Raw idempotency key не хранится: `orders.idempotency_key_hash` — HMAC-SHA256 ключом стабильным на весь жизненный цикл фикстуры/отчёта. Fingerprint тела и hashes конфигураций — SHA-256 (32 байта SQL bytea, 64 hex в JSON). UUID не имеют смыслового кодирования target/scenario. Все бизнес-Id ограничены безопасным целочисленным диапазоном API; технические UUID не преобразуются в bigint.

Времена корзины, intent, заказа и погашения задаются бизнес-часами. Checkout использует один captured business timestamp для проверки срока, `orders.created_at`, которое одновременно является временем погашения купона. Для купона и intent требуется `now < expires_at`; равенство уже означает истечение. Время событий, ledger, outbox, leases и HTTP — реальное UTC. Разность этих двух шкал не применяется для измерения latency.

### Обязательная транзакционная дисциплина

Порядок блокировок protected: idempotency scope → cart → intent → coupon. На успешный повтор сохранённый ответ возвращается до проверки истёкшего intent. Ключ сериализуется через pg_advisory_xact_lock(lockId) до чтения orders по user_id и полному idempotency_key_hash; чтение выполняется отдельным statement READ COMMITTED после получения lock. Блокировка транзакционная, освобождается при commit/rollback. lockId — первые 8 байт SHA-256 канонического UTF-8 JSON-массива ["checkout-v1", userId как десятичная строка, lowercase hex key hash], интерпретированные как signed big-endian long. Коллизия сокращенного lockId только сериализует разные ключи; решение принимается по полному hash и уникальному индексу, не по lockId. Timeout ожидания дает OPERATION_IN_PROGRESS, а не новую попытку создать заказ.

Условное обновление купона проверяет остаток и срок в том же UPDATE, в котором увеличивает счетчик. Order/items с полями key/response_body, отметка intent, очистка корзины, увеличенная revision, terminal ledger и событие outbox коммитятся вместе. До транзакции durable ACTIVE ledger уже существует. После rollback ошибка фиксируется отдельной короткой транзакцией ledger/outbox. Недоставленное событие нельзя удалить или признать доставленным без подтверждения идентичного payload в audit.

`terminal_event_id` — логическая ссылка, не FK на outbox: доставленная outbox-запись может быть очищена позже, тогда как audit остается доказательством. UUID события сохраняется при retry. Очистка возможна только после закрытия/экспорта и проверки coverage; time-based TTL не должен уничтожать незавершенное evidence.

### Почему магазину достаточно 10 таблиц

users, products, coupons, carts, cart_items, checkout_intents, orders, order_items, security_outbox, execution_ledger. Заказ хранит успешный ответ и ключ, поэтому отдельной таблицы результатов идемпотентности нет. Заказ с coupon_id одновременно фиксирует расход купона; отдельный журнал погашений потребуется только при добавлении отмен, возвратов или нескольких погашений на один заказ.

Перед INSERT order приложение получает id из identity sequence, строит полный Order response по серверному snapshot и вставляет заказ уже с response_body. Строки order_items и остальной эффект фиксируются в той же транзакции. Пропуски sequence после rollback допустимы. Для повторов возвращается сохраненный JSONB body с 201 и Idempotency-Replayed:true. До ответа текущий request получает terminal ledger и событие REPLAYED/stateChanged=false в outbox, после чего транзакция коммитится и освобождает advisory lock. Сохраняются типизированные значения ответа, не HTTP-заголовки/requestId прошлого attempt. В vulnerable тот же набор полей обеспечивает наблюдаемость, но advisory-lock, lookup прежнего результата и business UNIQUE намеренно отключены.

## Независимые SQL-проверки

`check_business_invariants(baseline jsonb)` возвращает только найденные нарушения/отсутствующие ссылки. Контракт результата: `(invariant_id, entity, entity_id, details)`. Функция readonly и использует numeric для промежуточных сумм, чтобы недопустимое уязвимое состояние не приводило к переполнению самого oracle. Проверки выполняются после quiescence в согласованном readonly snapshot; до запуска тот же набор проверяет baseline.

Baseline приходит из приватного `control.runs.fixture_manifest`, не из ответа shop или detector. Его обязательная часть для функции:

```text
catalogVersions: [{product_id, catalog_version, price, published}]
couponVersions: [{coupon_id, terms_version, discount_percent, expires_at}]
couponCounters: [{coupon_id, used_count}]
orderIds: [id исторических заказов до run]
```

Manifest содержит все версии, использованные историей и планом. Эти массивы — спецификация структуры, не файл наполнения. Перед SQL evaluator проверяет schema/version/hash, полноту и уникальность пар id/version, наличие счетчика каждого купона и точность baseline orderIds. Отсутствующий/невалидный baseline не подменяется пустым объектом: результат INCONCLUSIVE. В ходе run каталог/условия либо неизменяемы, либо все запланированные изменения и версии заранее присутствуют в независимом эталоне; неизвестная версия не признается корректной автоматически.

| Группа | Проверки |
|---|---|
| Корзина/заказ | INV-CART-QTY, INV-CART-SIZE, INV-ORDER-QTY, INV-ORDER-NONEMPTY |
| Стоимость | INV-ORDER-SUBTOTAL, INV-ORDER-TOTAL, INV-ORDER-PRICE, INV-ORDER-DISCOUNT |
| Купон | INV-COUPON-EXPIRED, INV-COUPON-LIMIT, INV-COUPON-COUNT |
| Одноразовый эффект | INV-INTENT-ONCE, INV-CART-REVISION-ONCE, INV-INTENT-CONSUMED |
| Связи/повторы | INV-ORDER-OWNERSHIP, INV-KEY-COMPLETE, INV-KEY-ONCE |
| Неполный эталон | ORACLE-REFERENCE-MISSING — причина INCONCLUSIVE, не доказательство успешной атаки |

Цены и скидки сравниваются с версиями из baseline, никогда с текущими products.price/coupons.discount_percent. В MVP один купон на заказ, нет отмен/возвратов: заказ с coupon_id сам является фактом погашения. Счетчик проверяется как baseline used_count + число новых заказов с этим coupon_id (baseline orderIds исключаются). Дополнительно отдельно сверяется история baseline; счетчик не выводится только из числа новых событий. Наличие FK не доказывает совпадение owners и intent/cart/revision — это отдельная проверка.

SQL-проверки не устанавливают факт утечки при BOLA, успешный вход по найденной паре или выдачу draft canary. Эти эффекты evaluator устанавливает по actor/ownership manifest и allowlist полей HTTP-response evidence. HTTP 200 и incident сами по себе не доказывают эффект. Отсутствие строк oracle означает отсутствие найденных нарушений только при полном эталоне и покрытии; оно не заменяет coverage barrier.

<!-- BEGIN DATA DICTIONARY -->
## Физический словарь данных

Ниже перечислены все столбцы. `—` означает отсутствие DEFAULT: значение передаёт приложение. UUID создаются приложением. Все FK имеют ON UPDATE NO ACTION; ON DELETE RESTRICT, кроме явно указанных CASCADE. JSONB валидируется по зафиксированной версии контракта до записи; сам JSONB не обеспечивает проверку предметных полей.

### `users`

Учетная запись; регистрация и создание корзины атомарны.

PK: `id`.
UNIQUE: `login`; `email`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `id` | `bigint IDENTITY` | нет | — | Идентификатор пользователя |
| `login` | `varchar(32)` | нет | — | Нормализованный login |
| `name` | `varchar(100)` | нет | — | Отображаемое имя |
| `email` | `varchar(254)` | нет | — | Нормализованный email |
| `password_hash` | `varchar(255)` | нет | — | PasswordEncoder hash; недоступен evaluator |

### `products`

Текущий каталог; изменения цены увеличивают catalog_version.

PK: `id`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `id` | `bigint IDENTITY` | нет | — | Идентификатор товара |
| `name` | `varchar(120)` | нет | — | Название |
| `description` | `text` | нет | `''` | Описание |
| `price` | `bigint` | нет | — | Цена в целых рублях |
| `published` | `boolean` | нет | `false` | Видимость в нормативном API |
| `catalog_version` | `bigint` | нет | `1` | Версия условий товара |

Общие CHECK: `price BETWEEN 1 AND 1000000000`; `catalog_version > 0`; `length(description) <= 4000`.

### `coupons`

Текущие условия; счетчик не меняет terms_version.

PK: `id`.
UNIQUE: `code`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `id` | `bigint IDENTITY` | нет | — | Идентификатор купона |
| `code` | `varchar(32)` | нет | — | Нормализованный код |
| `discount_percent` | `integer` | нет | — | Процент скидки |
| `expires_at` | `timestamptz` | нет | — | Исключительная граница по бизнес-часам |
| `max_uses` | `bigint` | нет | — | Лимит погашений |
| `used_count` | `bigint` | нет | `0` | Счетчик погашений |
| `terms_version` | `bigint` | нет | `1` | Версия условий |

Общие CHECK: `discount_percent BETWEEN 1 AND 100`; `max_uses > 0`; `terms_version > 0`.

### `carts`

Не более одной корзины на пользователя; наличие обеспечивает регистрация.

PK: `id`.
UNIQUE: `user_id`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `id` | `bigint IDENTITY` | нет | — | Идентификатор корзины |
| `user_id` | `bigint` | нет | — | Владелец; FK → `users.id` |
| `coupon_id` | `bigint` | да | — | Выбранный купон; FK → `coupons.id` |
| `revision` | `bigint` | нет | `0` | Версия после фактического изменения |
| `updated_at` | `timestamptz` | нет | — | Бизнес-время изменения |

Общие CHECK: `revision BETWEEN 0 AND 9007199254740991`.

Дополнительные индексы: `(coupon_id)`.

### `cart_items`

Позиции текущей корзины.

PK: `cart_id, product_id`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `cart_id` | `bigint` | нет | — | Корзина; FK → `carts.id CASCADE` |
| `product_id` | `bigint` | нет | — | Товар; FK → `products.id` |
| `quantity` | `integer` | нет | — | Количество; бизнес-границы только protected |

Дополнительные индексы: `(product_id)`.

### `checkout_intents`

Снимок расчета; не является независимым эталоном evaluator.

PK: `id`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `id` | `uuid` | нет | — | Случайный UUID, создается приложением |
| `user_id` | `bigint` | нет | — | Владелец; FK → `users.id` |
| `cart_id` | `bigint` | нет | — | Корзина; FK → `carts.id` |
| `cart_revision` | `bigint` | нет | — | Версия корзины на момент расчета |
| `snapshot_version` | `integer` | нет | `1` | Версия структуры snapshot |
| `snapshot` | `jsonb` | нет | — | Позиции, цены, версии, купон и суммы |
| `fingerprint` | `bytea` | нет | — | SHA-256 канонического snapshot |
| `created_at` | `timestamptz` | нет | — | Бизнес-время создания |
| `expires_at` | `timestamptz` | нет | — | Бизнес-время истечения |
| `consumed_order_id` | `bigint` | да | — | Первый потребивший заказ; NULL до успеха; FK → `orders.id` |

Общие CHECK: `octet_length(fingerprint) = 32`; `jsonb_typeof(snapshot) = 'object'`; `snapshot_version = 1`; `expires_at > created_at`.

Дополнительные индексы: `(cart_id, cart_revision)`; `(user_id)`; `(consumed_order_id)`.

### `orders`

Неизменяемый эффект checkout, успешный ответ и погашение купона; один купон на заказ, отмен нет.

PK: `id`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `id` | `bigint IDENTITY` | нет | — | Идентификатор заказа |
| `user_id` | `bigint` | нет | — | Владелец; FK → `users.id` |
| `cart_id` | `bigint` | нет | — | Исходная корзина; FK → `carts.id` |
| `cart_revision` | `bigint` | нет | — | Потребленная версия корзины |
| `intent_id` | `uuid` | нет | — | Исходный intent; FK → `checkout_intents.id` |
| `status` | `varchar(16)` | нет | `'CREATED'` | Единственный статус версии 1 |
| `subtotal` | `bigint` | нет | — | Стоимость до скидки |
| `discount` | `bigint` | нет | — | Скидка в рублях |
| `total` | `bigint` | нет | — | Итог в рублях |
| `coupon_id` | `bigint` | да | — | Купон; NULL вместе со всем snapshot; FK → `coupons.id` |
| `coupon_code` | `varchar(32)` | да | — | Код на момент оформления |
| `coupon_percent` | `integer` | да | — | Процент на момент оформления |
| `coupon_expires_at` | `timestamptz` | да | — | Срок на момент оформления |
| `coupon_terms_version` | `bigint` | да | — | Версия условий |
| `created_at` | `timestamptz` | нет | — | Бизнес-время принятия решения checkout |
| `idempotency_key_hash` | `bytea` | нет | — | HMAC-SHA256 исходного key; scope POST /orders + user |
| `request_fingerprint` | `bytea` | нет | — | SHA-256 канонического request с версией и route |
| `response_body` | `jsonb` | нет | — | Сохраненный Order response; status всегда 201 |

Общие CHECK: `status = 'CREATED'`; `octet_length(idempotency_key_hash) = 32`; `octet_length(request_fingerprint) = 32`; `jsonb_typeof(response_body) = 'object'`.

Дополнительные индексы: `(user_id, created_at DESC, id DESC)`; `(intent_id)`; `(cart_id, cart_revision)`; `(coupon_id)`; `(user_id, idempotency_key_hash)`.

### `order_items`

Неизменяемые позиции; цена и название зафиксированы.

PK: `order_id, product_id`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `order_id` | `bigint` | нет | — | Заказ; FK → `orders.id CASCADE` |
| `product_id` | `bigint` | нет | — | Исходный товар; FK → `products.id` |
| `quantity` | `integer` | нет | — | Количество |
| `name_snapshot` | `varchar(120)` | нет | — | Название при оформлении |
| `unit_price` | `bigint` | нет | — | Цена при оформлении |
| `catalog_version` | `bigint` | нет | — | Версия цены |

Дополнительные индексы: `(product_id)`.

### `security_outbox`

Неизменяемое содержимое события, изменяемое состояние доставки.

PK: `event_id`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `event_id` | `uuid` | нет | — | Глобальный UUID события |
| `producer_epoch` | `uuid` | нет | — | Зарегистрированная эпоха; логическая межбазовая ссылка |
| `payload` | `jsonb` | нет | — | CloudEvents v1, без секретов |
| `created_at` | `timestamptz` | нет | — | Реальное время записи |
| `delivered_at` | `timestamptz` | да | — | Подтверждение идентичного события в audit |
| `retry_count` | `integer` | нет | `0` | Количество повторов |
| `next_attempt_at` | `timestamptz` | нет | — | Следующая попытка доставки |

Общие CHECK: `retry_count >= 0`; `jsonb_typeof(payload) = 'object'`.

Дополнительные индексы: `(producer_epoch)`.

### `execution_ledger`

ACTIVE фиксируется до исполнения; terminal вместе с outbox.

PK: `request_id`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `request_id` | `uuid` | нет | — | UUID HTTP-attempt |
| `producer_epoch` | `uuid` | нет | — | Эпоха принятой работы |
| `state` | `varchar(16)` | нет | `'ACTIVE'` | ACTIVE / COMPLETED / ABORTED |
| `terminal_event_id` | `uuid` | да | — | Логическая ссылка; не FK на удаляемую outbox |
| `started_at` | `timestamptz` | нет | — | Реальное время принятия |
| `finished_at` | `timestamptz` | да | — | Реальное время terminal transition |

Общие CHECK: `state IN ('ACTIVE','COMPLETED','ABORTED')`; `(state = 'ACTIVE' AND terminal_event_id IS NULL AND finished_at IS NULL) OR (state <> 'ACTIVE' AND terminal_event_id IS NOT NULL AND finished_at IS NOT NULL)`.

Дополнительные индексы: `(producer_epoch, state)`.

<!-- END DATA DICTIONARY -->
