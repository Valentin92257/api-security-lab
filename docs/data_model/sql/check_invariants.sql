-- Read-only представление для проверки инвариантов: каждая строка - нарушенный инвариант.
-- Для protected ожидается пустой результат; для vulnerable - строки после успешных атак.

CREATE OR REPLACE VIEW invariant_violations AS
SELECT 'INV-CART-QTY'::text                       AS invariant_id,
       'cart_item'::text                          AS entity,
       (ci.cart_id || ':' || ci.product_id)::text AS entity_id,
       ('quantity=' || ci.quantity)::text         AS details
FROM cart_items ci
WHERE ci.quantity < 1
   OR ci.quantity > 99
UNION ALL
SELECT 'INV-ORDER-QTY', 'order_item', oi.order_id || ':' || oi.product_id, 'quantity=' || oi.quantity
FROM order_items oi
WHERE oi.quantity <= 0
UNION ALL
SELECT 'INV-ORDER-NONEMPTY', 'order', o.id::text, 'order has no items'
FROM orders o
WHERE NOT EXISTS (SELECT 1 FROM order_items oi WHERE oi.order_id = o.id)
UNION ALL
SELECT 'INV-ORDER-PRICE',
       'order_item',
       oi.order_id || ':' || oi.product_id,
       'unit_price=' || oi.unit_price || ' catalog=' || p.price
FROM order_items oi
         JOIN products p ON p.id = oi.product_id
WHERE oi.unit_price <> p.price
UNION ALL
SELECT 'INV-ORDER-SUBTOTAL', 'order', o.id::text, 'subtotal=' || o.subtotal || ' items=' || s.items_sum
FROM orders o
         JOIN (SELECT order_id, SUM(quantity::bigint * unit_price) AS items_sum
               FROM order_items
               GROUP BY order_id) s ON s.order_id = o.id
WHERE o.subtotal <> s.items_sum
UNION ALL
SELECT 'INV-ORDER-TOTAL',
       'order',
       o.id::text,
       'total=' || o.total || ' subtotal=' || o.subtotal || ' discount=' || o.discount
FROM orders o
WHERE o.total <> o.subtotal - o.discount
   OR o.total < 0
UNION ALL
SELECT 'INV-ORDER-DISCOUNT',
       'order',
       o.id::text,
       'discount=' || o.discount || ' expected=' || COALESCE(o.subtotal::bigint * c.discount_percent / 100, 0)
           || ' subtotal=' || o.subtotal
FROM orders o
         LEFT JOIN coupons c ON c.id = o.coupon_id
WHERE o.discount <> COALESCE(o.subtotal::bigint * c.discount_percent / 100, 0)
   OR o.discount < 0
   OR o.discount > o.subtotal
UNION ALL
SELECT 'INV-COUPON-EXPIRED',
       'order',
       o.id::text,
       'coupon ' || c.code || ' expired ' || c.expires_at || ' order at ' || o.created_at
FROM orders o
         JOIN coupons c ON c.id = o.coupon_id
WHERE c.expires_at < o.created_at
UNION ALL
SELECT 'INV-COUPON-LIMIT', 'coupon', c.id::text, 'used=' || c.used_count || ' max=' || c.max_uses
FROM coupons c
WHERE c.used_count < 0
   OR c.used_count > c.max_uses
UNION ALL
SELECT 'INV-COUPON-COUNT',
       'coupon',
       c.id::text,
       'used_count=' || c.used_count || ' orders=' || COALESCE(r.cnt, 0)
FROM coupons c
         LEFT JOIN (SELECT coupon_id, COUNT(*) AS cnt FROM orders WHERE coupon_id IS NOT NULL GROUP BY coupon_id) r
                   ON r.coupon_id = c.id
WHERE c.used_count <> COALESCE(r.cnt, 0);