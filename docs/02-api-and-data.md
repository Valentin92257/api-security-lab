# Минимальное API магазина и данные

## Границы API

Магазин предоставляет девять операций, необходимых для экспериментов ИБ. Отдельная карточка товара, история заказов и удаление позиций/купона исключены из MVP. Пользователей, корзины, каталог, купоны и исторические заказы подготавливает fixture-модуль control до run; регистрация также доступна отдельной публичной операцией; API наполнения каталога и купонов нет. Это осознанное сокращение первоначального функционального объёма магазина.

| Эксперимент | Необходимые операции |
|---|---|
| Credential stuffing | Login и чтение собственного профиля для подтверждения доступа |
| BOLA/IDOR | Чтение профиля и заказа по ID, известным из приватного fixture manifest |
| Injection | Поиск товаров по q; независимый признак эффекта — выдача draft canary |
| Replay | Подготовка корзины и повтор POST /orders с тем же или новым ключом |
| Business abuse | Недопустимое quantity, повторный/истёкший/исчерпанный купон, подмена суммы, гонки checkout |

## Общие правила

Один нормативный контракт описывает vulnerable и protected. Protected выполняет все правила; vulnerable намеренно отключает перечисленные ниже проверки для учебных сценариев. Базовый путь — `/api/v1`, внутренние origins — `http://lab-gateway:8081` и `http://lab-gateway:8082`. Версия машинного контракта — OpenAPI 3.1.0.

JSON UTF-8, camelCase, даты UTC RFC 3339. Все поля описанных request/response objects обязательны, кроме явно отмеченных optional; null разрешён только явно. Request objects закрыты: неизвестные/повторяющиеся JSON-поля и query parameters, дубли query names, несоответствие типов — 400. Преобразование строки в число не выполняется. Для операций с телом требуется `Content-Type: application/json`; для GET тело не предусмотрено. Ответы JSON, ошибки `application/problem+json`.

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

Поля тел обязательны; пустые тела не подменяются значениями по умолчанию. Во всех строках, кроме register/login, требуется JWT.

| Метод и путь | Тело / параметры | Ответ и семантика |
|---|---|---|
| `POST /auth/register` | `{login:string, password:string, name:string, email:string}` | 201 User; пользователь и пустая корзина создаются атомарно; токен получают через login |
| `POST /auth/login` | `{login:string, password:string}` | 200 LoginResult; неверная пара — 401 INVALID_CREDENTIALS |
| `GET /users/{userId}` | userId: Id | 200 собственный User; иначе 404 |
| `GET /products` | q, limit, offset — optional | 200 Page<Product>, только опубликованные товары |
| `GET /cart` | Нет | 200 Cart текущего пользователя |
| `PUT /cart/items/{productId}` | productId:Id; `{quantity:Quantity}` | 200 Cart; создаёт позицию или устанавливает абсолютное количество |
| `PUT /cart/coupon` | `{code:string}` | 200 Cart; выбирает/заменяет единственный купон, использование не расходует |
| `POST /orders` | `{cartRevision:Revision, quotedTotal:Money}` + Idempotency-Key | 201 Order; успешный retry возвращает тот же status/body |
| `GET /orders/{orderId}` | orderId: Id | 200 собственный Order; иначе 404 |

Login: 3…32 ASCII-символа `[a-z0-9_]+` после trim/lowercase; одинаковая нормализация регистрации, fixture, поиска пользователя и rate key. Пароль: 8…72 UTF-8 bytes, без trim/нормализации, никогда не возвращается. Name: 1…100 символов после trim. Email: синтаксически корректный адрес до 254 символов; trim/lowercase всего адреса. Нормализованные login/email уникальны; в fixture и регистрации используются только синтетические данные. Эти поля доступны в профиле как данные для проверки утечки при BOLA. Coupon code: 1…32 символа `[A-Z0-9_-]` после trim/uppercase; неизвестный код — 404.

Поиск q — строка 0…120 символов, default пустая; регистронезависимое буквальное вхождение в name или description, `%`/`_` не являются пользовательскими wildcard. Сортировка фиксирована: id по возрастанию; параметра sort нет. Limit 1…100, default 20; offset 0…10000, default 0. Invalid id/range — 400; неизвестный маршрут — 404; неподдерживаемый метод известного маршрута — 405 с Allow.

Регистрация работает одинаково в обоих режимах: валидирует поля, хеширует пароль через PasswordEncoder, в одной транзакции создаёт User и пустую Cart с revision=0, coupon=null. Ошибка или конфликт уникальности откатывает обе записи; при одновременном конфликте login/email приоритет имеет LOGIN_TAKEN. Пароль и hash не возвращаются и не попадают в логи. Повтор успешной регистрации с теми же данными даёт 409, а не новую учётную запись.

## Объекты ответа

| Объект | Полный состав полей |
|---|---|
| `User` | id:Id, login:string, name:string, email:string |
| `LoginResult` | accessToken:string, tokenType:"Bearer", expiresIn:integer — оставшийся срок в секундах |
| `Product` | id:Id, name:string(1…120), description:string(0…4000), price:Money > 0, currency:"RUB" |
| `LineItem` | productId:Id, name:string, quantity:Quantity, unitPrice:Money > 0, lineTotal:Money |
| `CartCoupon` | code:string, discountPercent:integer(1…100), eligibility:"VALID"/"EXPIRED"/"EXHAUSTED" |
| `OrderCoupon` | code:string, discountPercent:integer(1…100); условия купона при оформлении |
| `Cart` | id:Id, userId:Id, revision:Revision, items:LineItem[], coupon:CartCoupon/null, subtotal:Money, discount:Money, total:Money, currency:"RUB", updatedAt:Time |
| `Order` | id:Id, userId:Id, cartId:Id, cartRevision:Revision, status:"CREATED", items:LineItem[] непустой, coupon:OrderCoupon/null, subtotal:Money, discount:Money, total:Money, currency:"RUB", createdAt:Time |

В Cart цены из неизменяемого каталога; в Order цена позиции сохранена в unit_price. LineTotal всегда quantity × unitPrice. Пустая корзина имеет items=[], subtotal=discount=total=0. CartCoupon выбирается без резерва: PUT проверяет доступность сейчас, checkout проверяет повторно. Если купон стал недоступен, GET cart сохраняет code и percent, показывает соответствующий eligibility и discount=0. Оформление с недоступным купоном отклоняется.

Количество 0 не означает удаление: это отрицательный security-кейс, protected возвращает 400. Для пустой корзины используется отдельный fixture-пользователь либо очистка при успешном protected checkout. Сброс фикстуры разрешён только после завершения run.

Корзина принадлежит JWT subject; ownerId/userId не принимаются в её mutation requests. Draft/отсутствующий товар нельзя добавить. Превышение 99 в существующей позиции или 100 разных товаров — 400 CART_LIMIT_EXCEEDED. Изменение состава/quantity/купона увеличивает revision; повтор PUT с тем же quantity или выбор того же купона её не меняет. Отмен заказов, платежей и возвратов в версии 1 нет. Hash пароля, flags published/canary и внутренние fingerprints в публичные ответы не входят.

## Checkout и повторы

Клиент получает Cart и отправляет `POST /orders` с `{cartRevision, quotedTotal}` и заголовком Idempotency-Key. Cart выбирается по JWT subject; cartId/userId клиент не задаёт. Сервер проверяет revision и сам рассчитывает стоимость. Каталог и условия купонов неизменяемы от подготовки фикстуры до сохранения отчёта; меняются корзины и used_count. Срок купона продолжает истекать по бизнес-часам.

Key действует в scope `(authenticated user, method, route, key)`. Fingerprint — SHA-256 версии контракта, маршрута и канонического разобранного тела. Порядок полей не влияет; JWT/requestId/trace не включаются. Успех сохраняет в orders idempotency_key_hash, request_fingerprint и полный response_body; статус всегда 201. Ошибки не кешируются.

| Условие | Результат protected |
|---|---|
| Тот же key и fingerprint после успеха | Прежние 201/Order без нового эффекта, даже после изменения корзины или истечения купона |
| Тот же key, другое тело | 409 IDEMPOTENCY_CONFLICT |
| Новый key, уже оформленная cartRevision | 409 CART_ALREADY_CHECKED_OUT |
| Параллельный тот же key | Ожидание commit; прежний успех либо 409 OPERATION_IN_PROGRESS после ограниченного ожидания |
| Два разных key одной cartRevision | Один order; второй 409 CART_ALREADY_CHECKED_OUT |
| Revision не текущая и ранее не оформлялась | 409 CART_CHANGED |
| Пустая текущая корзина | 409 CART_EMPTY |
| Неверный quotedTotal | 409 PRICE_CHANGED |
| Купон истёк / исчерпан | 409 COUPON_EXPIRED / COUPON_EXHAUSTED |

Lookup успешного key выполняется после auth/schema validation, до проверки текущей корзины и купона. После timeout повторяются тот же key/body. Заказы и их ключи сохраняются до завершения фикстуры и отчёта. PUT количества задаёт абсолютное значение и при неизменном состоянии не повышает revision. Между чтением корзины и checkout её изменение другим запросом обнаруживается по cartRevision. PUT не гарантирует однократность бизнес-эффекта checkout — для него обязателен Idempotency-Key.

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
| 409 | LOGIN_TAKEN, EMAIL_TAKEN — занятый нормализованный login/email; конфликты checkout из таблицы выше |
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
| `products` | id PK; name varchar(120); description text; price; published boolean |
| `coupons` | id PK; code varchar(32) UNIQUE; discount_percent integer; expires_at; max_uses/used_count bigint |
| `carts` | id PK; user_id FK UNIQUE; coupon_id FK?; revision; updated_at |
| `cart_items` | cart_id FK, product_id FK, quantity integer; PK(cart_id, product_id) |
| `orders` | id PK; user_id/cart_id FK; cart_revision; status CREATED; subtotal/discount/total; coupon_id FK?; created_at; idempotency_key_hash; request_fingerprint; response_body |
| `order_items` | order_id/product_id FK; quantity; unit_price; PK(order_id, product_id) |

Успешный response_body хранится в заказе и содержит полный ответ, включая названия позиций и данные купона. Отдельные столбцы снимков не нужны: каталог и условия купонов фиксированы на run. unit_price сохраняет цену покупки. Без coupon_id скидка равна нулю. Индексы покрывают историю пользователя, ключ идемпотентности, cart/revision и внешние ключи.

Protected constraints: quantity 1…99, положительная unit_price, неотрицательные суммы, discount≤subtotal, total=subtotal−discount, used_count от 0 до max_uses; UNIQUE(user_id, idempotency_key_hash) и UNIQUE(cart_id, cart_revision) в orders. Общие PK/FK, уникальность пользователей и допустимые параметры каталога/купона сохраняются в обоих режимах. Защитные constraints устанавливаются только protected migration set. Межтабличная согласованность обеспечивается транзакциями и проверяется отдельно.

## Атомарное оформление

Все mutations корзины берут её row lock. При READ COMMITTED порядок protected checkout фиксирован:

1. Взять транзакционный advisory lock scope ключа, затем прочитать orders; для прежнего успеха сравнить fingerprint и вернуть сохранённый ответ.
2. Заблокировать корзину JWT subject. Проверить наличие orders с этой cart_id и запрошенной cartRevision: при наличии вернуть CART_ALREADY_CHECKED_OUT. Затем проверить текущую revision и непустой состав.
3. Проверить quantity и published, рассчитать стоимость по каталогу и сверить quotedTotal. Для купона бизнес-время должно быть строго меньше expires_at; равенство означает истечение.
4. Для купона выполнить условное атомарное увеличение used_count при действующем сроке и остатке; ноль обновлённых строк означает конфликт.
5. Создать order/items с исходной cart_revision, очистить позиции и coupon_id корзины, увеличить revision.
6. Проверить сохраненный в order response_body и выполнить commit. После подтверждения commit записать business result и terminal JSONL-событие; затем вернуть HTTP-ответ.

Порядок locks: key → cart → coupon, внутри одного типа — возрастающий id. Обратный захват не допускается. Ошибка до commit откатывает всё; ошибка ответа после commit разрешается retry того же key. Событие успеха пишется в JSONL только после commit; отказ — после rollback. Лог и бизнес-данные не атомарны. При ошибке логирования run признается недействительным, а уже committed заказ не отменяется. Однократность бизнес-эффекта обеспечивается транзакциями и ограничениями PostgreSQL.

Vulnerable использует именованные стратегии: пропуск owner-check; ограниченный SQL-каталог с конкатенацией; принятие недопустимого quantity/quotedTotal; неатомарный купон; пропуск key lookup и проверки уже оформленной cartRevision. Для воспроизводимого replay vulnerable намеренно не очищает корзину/купон и не увеличивает revision после checkout: тот же запрос создаёт ещё один order. Это отдельная учебная слабость; обычные mutations корзины повышают revision в обоих режимах. JSON syntax/ресурсные пределы общие; автоматический `@Valid` не должен закрыть учебные слабости в обоих режимах. Catalog datasource имеет только SELECT products.

## Независимые проверки и фикстуры

| Свойство | Доказательство evaluator |
|---|---|
| Чужие profile/order не раскрыты | JWT subject сценария, owner из приватного manifest, фактически возвращённые поля |
| Допустимые количества, непустой order | Readonly cart/order items |
| Верные цены и скидки | Независимый расчёт по приватному baseline неизменяемого каталога и условий купонов |
| Корректные суммы | Сумма строк и целочисленная формула discount/total |
| Купон | Время операции < expiry; used_count равен baseline + числу новых заказов с купоном и не превышает max_uses; один купон на order |
| Один эффект | Не более одного нового order на cart revision |
| Видимость каталога | Draft canary отсутствует в нормативной выдаче |
| Доступ после login | Выданный token действительно открывает профиль; один status 200 недостаточен |

Evaluator не вызывает production calculator и не доверяет локальному признаку «проверка пройдена». Цены/купоны сверяются с независимым baseline, а не с ответом shop или текущими таблицами. Исторические fixture orders соответствуют тому же каталогу и условиям купонов. Успех атакующего и нарушение гарантии различаются: первая корректная пара credentials может дать доступ при правильно работающем JWT.

Генератор fixture v1 создаёт 24 синтетических пользователя с корзинами, 12 опубликованных товаров и один draft canary. Пять купонных состояний: действующий, expired, exhausted, последнее доступное использование, 100% скидка. Начальная история обеспечивает собственные/чужие orders и согласованные счетчики купонов; каждому order назначены отдельные исторические cart revisions. Хеши паролей формирует PasswordEncoder; пустые hashes запрещены.

Credential workload — 200 попыток с тремя корректными парами разных аккаунтов. Порядок, synthetic actors и значения воспроизводятся по seed; повторяемость password hash байт-в-байт не нужна. Manifest хранит version/hash, seed, businessTimeBase, ids/owners, начальные counts и snapshots, без паролей в экспорте. Перед RUNNING проверяется исходная согласованность. Catalog/terms не изменяются внутри прогона; бизнес-часы имеют одинаковую UTC-базу paired runs и монотонное продвижение, JWT использует реальные часы.

Подготовка/reset доступны только control через lab-admin, после завершения всех операций. Runtime shop не имеет TRUNCATE. Конечное состояние и evidence сохраняются до сброса.

## Миграции и жизненный цикл данных

Исполняемая исходная схема PostgreSQL 17 находится в `data/shop-schema.sql`; только для `shop_protected` после неё применяется `data/shop-protected.sql`. Обе базы получают `data/shop-invariants.sql`. Это начальные миграции пустых баз, не повторно запускаемые repair scripts. В Flyway их переносят в db/shop/common, db/shop/protected и db/lab с однозначными version numbers; один SQL-файл соответствует одной миграции, повторно примененные версии не редактируются. Их выполняет migration owner; runtime-приложения не получают DDL. SQL не создаёт и не наполняет фикстуры.

Порядок создания: все таблицы → PK/UNIQUE/CHECK → FK после появления обеих сторон → индексы → protected-only constraints → readonly функции oracle. При reset данные очищаются административно в закрытой эпохе после сохранения доказательств; обычного API удаления истории нет.

Общие ограничения обеспечивают структурную целостность: типы, NOT NULL, идентификаторы, PK/FK, уникальность логинов/email/кодов, параметры каталога. Ограничения количества, итогов, счетчика купона и одноразового эффекта находятся только в protected migration. Общая схема не должна случайно устранять учебную уязвимость. Hash ключа, fingerprint и response_body входят в orders; UNIQUE(user_id,idempotency_key_hash) действует только в protected. Scope method/route не требует колонок: идемпотентность предусмотрена только для POST /api/v1/orders.

FK по умолчанию запрещают удаление родителя. CASCADE применяется только к составным частям `cart_items` и `order_items`; это не разрешение runtime удалять заказы. UNIQUE(carts.user_id) означает «не более одной корзины», а наличие корзины обеспечивает атомарная регистрация либо подготовка пользователя/корзины fixture-модулем. Непустой заказ, максимум 100 разных позиций, совпадение owner и сумма всех строк — межстрочные условия: их проверяет транзакционный код и независимый oracle. Они не выдаются за обычные CHECK одной строки.

Индексы PK/UNIQUE создаёт PostgreSQL. Дополнительные индексы для истории заказов, key scope и поиска по FK перечислены в словаре ниже. Индексы не заменяют блокировки и не обеспечивают межтабличные бизнес-инварианты.

### Snapshot, время и идентификаторы

Отдельного серверного объекта подготовки checkout нет. Cart.revision фиксирует состояние корзины, а orders.cart_revision — потреблённую версию. Каталог (включая названия, цены и published) и условия купонов (code, percent, expires_at, max_uses) заморожены до отчёта; used_count изменяем. GET /orders/{orderId} возвращает сохранённый Order response после owner-check. Изменение каталога во время run не поддерживается.

Объект `response_body` содержит полный нормативный Order response. Raw idempotency key не хранится: `orders.idempotency_key_hash` — HMAC-SHA256 ключом стабильным на весь жизненный цикл фикстуры/отчёта. Fingerprint тела и hashes конфигураций — SHA-256 (32 байта SQL bytea, 64 hex в JSON). UUID не имеют смыслового кодирования target/scenario. Все бизнес-Id ограничены безопасным целочисленным диапазоном API; технические UUID не преобразуются в bigint.

Времена корзины, заказа и погашения задаются бизнес-часами. Checkout использует один captured business timestamp для проверки срока, `orders.created_at`, которое одновременно является временем погашения купона. Для купона требуется `now < expires_at`; равенство уже означает истечение. Время логов, run deadlines и HTTP — реальное UTC. Разность этих двух шкал не применяется для измерения latency.

### Обязательная транзакционная дисциплина

Порядок блокировок protected: idempotency scope → cart → coupon. На успешный повтор сохранённый ответ возвращается до проверки текущей корзины и срока купона. Ключ сериализуется через pg_advisory_xact_lock(lockId) до чтения orders по user_id и полному idempotency_key_hash; чтение выполняется отдельным statement READ COMMITTED после получения lock. Блокировка транзакционная, освобождается при commit/rollback. lockId — первые 8 байт SHA-256 канонического UTF-8 JSON-массива ["checkout-v1", userId как десятичная строка, lowercase hex key hash], интерпретированные как signed big-endian long. Коллизия сокращенного lockId только сериализует разные ключи; решение принимается по полному hash и уникальному индексу, не по lockId. Timeout ожидания дает OPERATION_IN_PROGRESS, а не новую попытку создать заказ.

Условное обновление купона проверяет остаток и срок в том же UPDATE, в котором увеличивает счетчик. Order/items с key/response_body, очистка корзины и revision коммитятся вместе. Событие CHECKOUT/stateChanged=true создаётся по подтверждённому результату и записывается в JSONL после commit; при rollback пишется отказ/stateChanged=false. Счетчик активных запросов в JVM уменьшается только после terminal log.

Для логирования API использует библиотеку структурированных security-событий: EVENT JSONL после каждого наблюдения и STREAM_END при закрытии потока. Библиотека не меняет схему shop DB и не выполняет audit SQL. Полный контракт, порядок записи, flush и обработка ошибок заданы в документе 04.

### Почему магазину достаточно 7 таблиц

users, products, coupons, carts, cart_items, orders, order_items. Заказ хранит успешный ответ и ключ, поэтому отдельной таблицы результатов идемпотентности нет. Заказ с coupon_id одновременно фиксирует расход купона; отдельный журнал погашений потребуется только при добавлении отмен, возвратов или нескольких погашений на один заказ.

Перед INSERT order приложение получает id из identity sequence, строит полный Order response по серверному snapshot и вставляет заказ уже с response_body. Строки order_items и остальной эффект фиксируются в той же транзакции. Пропуски sequence после rollback допустимы. Для повторов возвращается сохраненный JSONB body с 201 и Idempotency-Replayed:true. Транзакция завершается и освобождает advisory lock; до ответа API пишет JSONL-событие REPLAYED/stateChanged=false и terminal log текущего request. Сохраненный бизнес-ответ не содержит trace/request ID прежней попытки. Сохраняются типизированные значения ответа, не HTTP-заголовки/requestId прошлого attempt. В vulnerable тот же набор полей обеспечивает наблюдаемость, но advisory-lock, lookup прежнего результата и business UNIQUE намеренно отключены.

## Независимые SQL-проверки

`check_business_invariants(baseline jsonb)` возвращает только найденные нарушения/отсутствующие ссылки. Контракт результата: `(invariant_id, entity, entity_id, details)`. Функция readonly и использует numeric для промежуточных сумм, чтобы недопустимое уязвимое состояние не приводило к переполнению самого oracle. Проверки выполняются после quiescence в согласованном readonly snapshot; до запуска тот же набор проверяет baseline.

Baseline приходит из приватного `control.runs.fixture_manifest`, не из ответа shop или detector. Его обязательная часть для функции:

```text
catalog: [{product_id, name, description, price, published}]
coupons: [{coupon_id, code, discount_percent, expires_at, max_uses}]
couponCounters: [{coupon_id, used_count}]
orderIds: [id исторических заказов до run]
```

Manifest содержит полный каталог, условия купонов и начальные счетчики. Перед SQL evaluator проверяет schema/version/hash, полноту и уникальность id, наличие счетчика каждого купона и точность baseline orderIds. Отсутствующий/невалидный baseline даёт INCONCLUSIVE. Изменение замороженных данных даёт ORACLE-FIXTURE-CHANGED и также INCONCLUSIVE; это нарушение условий эксперимента, а не доказанный успех атаки.

| Группа | Проверки |
|---|---|
| Корзина/заказ | INV-CART-QTY, INV-CART-SIZE, INV-ORDER-QTY, INV-ORDER-NONEMPTY |
| Стоимость | INV-ORDER-SUBTOTAL, INV-ORDER-TOTAL, INV-ORDER-PRICE, INV-ORDER-DISCOUNT |
| Купон | INV-COUPON-EXPIRED, INV-COUPON-LIMIT, INV-COUPON-COUNT |
| Одноразовый эффект | INV-CART-REVISION-ONCE |
| Связи/повторы | INV-ORDER-OWNERSHIP, INV-KEY-COMPLETE, INV-KEY-ONCE |
| Неполный эталон | ORACLE-REFERENCE-MISSING, ORACLE-FIXTURE-CHANGED — причина INCONCLUSIVE, не доказательство успешной атаки |

Цены и скидки сравниваются со значениями baseline, никогда с текущими products.price/coupons.discount_percent. В MVP один купон на заказ, нет отмен/возвратов: заказ с coupon_id сам является фактом погашения. Счетчик проверяется как baseline used_count + число новых заказов с этим coupon_id (baseline orderIds исключаются). Дополнительно отдельно сверяется история baseline; счетчик не выводится только из числа новых событий. Наличие FK не доказывает совпадение владельцев заказа и корзины — это отдельная проверка.

SQL-проверки не устанавливают факт утечки при BOLA, успешный вход по найденной паре или выдачу draft canary. Эти эффекты evaluator устанавливает по actor/ownership manifest и allowlist полей HTTP-response evidence. HTTP 200 и incident сами по себе не доказывают эффект. Отсутствие строк oracle означает отсутствие найденных нарушений только при полном эталоне и покрытии; оно не заменяет coverage barrier.

<!-- BEGIN DATA DICTIONARY -->
## Физический словарь данных

Ниже перечислены все столбцы. `—` означает отсутствие DEFAULT: значение передаёт приложение. UUID создаются приложением. Все FK имеют ON UPDATE NO ACTION; ON DELETE RESTRICT, кроме явно указанных CASCADE. JSONB валидируется по зафиксированной версии контракта до записи; сам JSONB не обеспечивает проверку предметных полей.

### `users`

Учетная запись; пользователя и корзину атомарно создаёт регистрация или fixture-модуль.

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

Каталог неизменяем внутри run и до сохранения отчета.

PK: `id`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `id` | `bigint IDENTITY` | нет | — | Идентификатор товара |
| `name` | `varchar(120)` | нет | — | Название |
| `description` | `text` | нет | `''` | Описание |
| `price` | `bigint` | нет | — | Цена в целых рублях |
| `published` | `boolean` | нет | `false` | Видимость в нормативном API |

Общие CHECK: `price BETWEEN 1 AND 1000000000`; `length(description) <= 4000`.

### `coupons`

Условия неизменяемы внутри run; used_count меняется при оформлении.

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

Общие CHECK: `discount_percent BETWEEN 1 AND 100`; `max_uses > 0`.

### `carts`

Не более одной корзины на пользователя; наличие обеспечивает регистрация или fixture-модуль.

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

### `orders`

Неизменяемый эффект checkout, успешный ответ и погашение купона; один купон на заказ, отмен нет.

PK: `id`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `id` | `bigint IDENTITY` | нет | — | Идентификатор заказа |
| `user_id` | `bigint` | нет | — | Владелец; FK → `users.id` |
| `cart_id` | `bigint` | нет | — | Исходная корзина; FK → `carts.id` |
| `cart_revision` | `bigint` | нет | — | Потребленная версия корзины |
| `status` | `varchar(16)` | нет | `'CREATED'` | Единственный статус версии 1 |
| `subtotal` | `bigint` | нет | — | Стоимость до скидки |
| `discount` | `bigint` | нет | — | Скидка в рублях |
| `total` | `bigint` | нет | — | Итог в рублях |
| `coupon_id` | `bigint` | да | — | Купон; NULL означает отсутствие скидки; FK → `coupons.id` |
| `created_at` | `timestamptz` | нет | — | Бизнес-время принятия решения checkout |
| `idempotency_key_hash` | `bytea` | нет | — | HMAC-SHA256 исходного key; scope POST /orders + user |
| `request_fingerprint` | `bytea` | нет | — | SHA-256 канонического request с версией и route |
| `response_body` | `jsonb` | нет | — | Сохраненный Order response; status всегда 201 |

Общие CHECK: `status = 'CREATED'`; `octet_length(idempotency_key_hash) = 32`; `octet_length(request_fingerprint) = 32`; `jsonb_typeof(response_body) = 'object'`.

Дополнительные индексы: `(user_id, created_at DESC, id DESC)`; `(cart_id, cart_revision)`; `(coupon_id)`; `(user_id, idempotency_key_hash)`.

### `order_items`

Неизменяемые позиции заказа с ценой на момент оформления.

PK: `order_id, product_id`.

| Поле | SQL-тип | NULL | DEFAULT | Назначение / связь |
|---|---|---|---|---|
| `order_id` | `bigint` | нет | — | Заказ; FK → `orders.id CASCADE` |
| `product_id` | `bigint` | нет | — | Исходный товар; FK → `products.id` |
| `quantity` | `integer` | нет | — | Количество |
| `unit_price` | `bigint` | нет | — | Цена при оформлении |

Дополнительные индексы: `(product_id)`.

<!-- END DATA DICTIONARY -->
