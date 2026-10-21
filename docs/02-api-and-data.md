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

Key действует в scope `(authenticated user, method, route, key)`. Fingerprint — SHA-256 версии контракта, маршрута и канонического разобранного тела: одинаковые типизированные значения при разном порядке полей дают один fingerprint. JWT/requestId/trace не включаются. Успешная запись key сохраняет status/body/orderId; неуспешная транзакция откатывается вместе с незавершённой записью key.

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
| `orders` | id PK; user_id/cart_id FK; cart_revision; intent_id FK; status CREATED; subtotal/discount/total; coupon_id FK?; coupon_code/percent/expires_at/terms_version snapshot?; created_at |
| `order_items` | order_id/product_id FK; quantity; name_snapshot; unit_price; catalog_version; PK(order_id, product_id) |
| `checkout_intents` | id UUID PK; user_id/cart_id FK; cart_revision; versioned snapshot JSONB; fingerprint; expires_at; consumed_order_id FK? |
| `idempotency_records` | user_id FK, method, route, key_hash; fingerprint; order_id FK?, response_status/body?; в protected UNIQUE(user_id, method, route, key_hash) |
| `coupon_redemptions` | id PK; coupon_id/order_id/intent_id FK; redeemed_at; UNIQUE(order_id) |
| `security_outbox` | event_id UUID PK; producer_epoch UUID; payload JSONB; created_at; delivered_at?; retry_count; next_attempt_at |
| `execution_ledger` | request_id UUID PK; producer_epoch; state ACTIVE/COMPLETED/ABORTED; terminal_event_id?; started_at; finished_at? |

Snapshot intent содержит revision, items с ценами/catalog versions, coupon с условиями/версией и все суммы. Coupon snapshot заказа nullable целиком: без купона discount=0. Связь order→intent создаётся до установки consumed_order_id; pending idempotency row никогда не коммитится без успешного response. Индексы: orders(user_id, created_at, id), cart_items по PK, outbox по недоставленным rows, ledger по epoch/state.

Protected constraints: quantity 1…99, положительная unit_price, неотрицательные суммы, discount≤subtotal, total=subtotal−discount, used_count от 0 до max_uses; UNIQUE(intent_id) и UNIQUE(cart_id, cart_revision) в orders. Общие PK/FK, уникальность пользователей и допустимые параметры каталога/купона сохраняются в обоих режимах. Защитные constraints устанавливаются только protected migration set. Межтабличная согласованность обеспечивается транзакциями и проверяется отдельно.

## Атомарное оформление

Все mutations корзины берут её row lock. При READ COMMITTED порядок protected checkout фиксирован:

1. Занять/проверить key; для прежнего успеха сравнить fingerprint и вернуть сохранённый ответ.
2. Заблокировать cart, затем intent. Проверить owner, consumed state, revision и expiry.
3. Сверить snapshot с текущим составом, catalog/terms versions и quotedTotal. Бизнес-время должно быть строго меньше expiry: равенство уже означает истечение.
4. Для купона выполнить условное атомарное увеличение used_count при действующем сроке и остатке; ноль обновлённых строк означает конфликт.
5. Создать order/items/redemption, отметить intent consumed, очистить корзину и увеличить revision.
6. Сохранить response key и событие операции в outbox; commit всех изменений вместе.

Порядок locks: key → cart → intent → coupon, внутри одного типа — возрастающий id. Обратный захват не допускается. Ошибка до commit откатывает всё; ошибка ответа после commit разрешается retry того же key. Успешная бизнес-операция и её outbox атомарны; событие отказа после rollback пишется отдельной короткой audit-транзакцией. Redis не участвует в защите однократного эффекта.

Vulnerable использует именованные стратегии: пропуск owner-check; ограниченный SQL-каталог с конкатенацией; принятие недопустимого quantity/quotedTotal; неатомарный купон; повторное исполнение snapshot intent без consumed-check. Последнее воспроизводит replay после очистки корзины. JSON syntax/ресурсные пределы общие; автоматический `@Valid` не должен закрыть учебные слабости в обоих режимах. Catalog datasource имеет только SELECT products.

## Независимые проверки и фикстуры

| Свойство | Доказательство evaluator |
|---|---|
| Чужие profile/order не раскрыты | JWT subject сценария, owner из приватного manifest, фактически возвращённые поля |
| Допустимые количества, непустой order | Readonly cart/order items |
| Верные цены и скидки | Независимый расчёт по trusted catalog/terms snapshot соответствующей версии |
| Корректные суммы | Сумма строк и целочисленная формула discount/total |
| Купон | Время операции < expiry; used_count равен baseline + новым redemptions и не превышает max_uses; один redemption на order |
| Один эффект | Не более одного нового order на intent и cart revision |
| Видимость каталога | Draft canary отсутствует в нормативной выдаче |
| Доступ после login | Выданный token действительно открывает профиль; один status 200 недостаточен |

Evaluator не вызывает production calculator и не доверяет локальному признаку «проверка пройдена». Исторические цены/купоны сверяются с независимым snapshot, а не с текущим каталогом. Успех атакующего и нарушение гарантии различаются: первая корректная пара credentials может дать доступ при правильно работающем JWT.

Генератор fixture v1 создаёт 24 синтетических пользователя с корзинами, 12 опубликованных товаров и один draft canary. Пять купонных состояний: действующий, expired, exhausted, последнее доступное использование, 100% скидка. Начальная история обеспечивает собственные/чужие orders и согласованные redemptions; каждому order назначены отдельные исторические intent/cart revisions. Хеши паролей формирует PasswordEncoder; пустые hashes запрещены.

Credential workload — 200 попыток с тремя корректными парами разных аккаунтов. Порядок, synthetic actors и значения воспроизводятся по seed; повторяемость password hash байт-в-байт не нужна. Manifest хранит version/hash, seed, businessTimeBase, ids/owners, начальные counts и snapshots, без паролей в экспорте. Перед RUNNING проверяется исходная согласованность. Catalog/terms не изменяются внутри прогона; бизнес-часы имеют одинаковую UTC-базу paired runs и монотонное продвижение, JWT использует реальные часы.

Подготовка/reset доступны только control через lab-admin, после завершения всех операций. Runtime shop не имеет TRUNCATE. Конечное состояние и evidence сохраняются до сброса.
