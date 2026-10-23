-- Install in each shop DB after shop-schema.sql. Read-only functions; no fixture writes.
BEGIN;
CREATE FUNCTION check_business_invariants(baseline jsonb)
RETURNS TABLE (invariant_id text, entity text, entity_id text, details jsonb)
LANGUAGE sql STABLE AS $$
WITH catalog AS (
  SELECT * FROM jsonb_to_recordset(baseline->'catalog')
    AS x(product_id bigint, name text, description text, price bigint, published boolean)
), terms AS (
  SELECT * FROM jsonb_to_recordset(baseline->'coupons')
    AS x(coupon_id bigint, code text, discount_percent integer, expires_at timestamptz, max_uses bigint)
), counters AS (
  SELECT * FROM jsonb_to_recordset(baseline->'couponCounters')
    AS x(coupon_id bigint, used_count bigint)
), baseline_orders AS (
  SELECT value::bigint AS id FROM jsonb_array_elements_text(baseline->'orderIds')
), sums AS (
  SELECT o.id, count(oi.product_id) AS item_count,
         coalesce(sum(oi.quantity::numeric * oi.unit_price::numeric),0) AS amount
  FROM orders o LEFT JOIN order_items oi ON oi.order_id=o.id GROUP BY o.id
)
SELECT 'INV-CART-QTY','cart_items',cart_id::text||'/'||product_id::text,
       jsonb_build_object('quantity',quantity)
FROM cart_items WHERE quantity NOT BETWEEN 1 AND 99
UNION ALL
SELECT 'INV-CART-SIZE','carts',cart_id::text,jsonb_build_object('count',count(*))
FROM cart_items GROUP BY cart_id HAVING count(*) > 100
UNION ALL
SELECT 'INV-ORDER-QTY','order_items',order_id::text||'/'||product_id::text,
       jsonb_build_object('quantity',quantity)
FROM order_items WHERE quantity NOT BETWEEN 1 AND 99
UNION ALL
SELECT 'INV-ORDER-NONEMPTY','orders',id::text,jsonb_build_object('itemCount',item_count)
FROM sums WHERE item_count=0 OR item_count>100
UNION ALL
SELECT 'INV-ORDER-SUBTOTAL','orders',o.id::text,jsonb_build_object('expected',s.amount,'actual',o.subtotal)
FROM orders o JOIN sums s ON s.id=o.id WHERE o.subtotal::numeric<>s.amount
UNION ALL
SELECT 'INV-ORDER-TOTAL','orders',id::text,jsonb_build_object('total',total)
FROM orders WHERE subtotal NOT BETWEEN 0 AND 9900000000000 OR discount<0 OR discount>subtotal
 OR total::numeric<>subtotal::numeric-discount::numeric
UNION ALL
SELECT 'ORACLE-REFERENCE-MISSING','order_items',oi.order_id::text||'/'||oi.product_id::text,
       jsonb_build_object('productId',oi.product_id)
FROM order_items oi LEFT JOIN catalog c ON c.product_id=oi.product_id
WHERE c.product_id IS NULL
UNION ALL
SELECT 'INV-ORDER-PRICE','order_items',oi.order_id::text||'/'||oi.product_id::text,
       jsonb_build_object('expected',c.price,'actual',oi.unit_price)
FROM order_items oi JOIN catalog c ON c.product_id=oi.product_id
WHERE oi.unit_price<>c.price OR NOT c.published
UNION ALL
SELECT 'ORACLE-REFERENCE-MISSING','orders',o.id::text,jsonb_build_object('couponId',o.coupon_id)
FROM orders o LEFT JOIN terms t ON t.coupon_id=o.coupon_id
WHERE o.coupon_id IS NOT NULL AND t.coupon_id IS NULL
UNION ALL
SELECT 'INV-ORDER-DISCOUNT','orders',o.id::text,jsonb_build_object('actual',o.discount)
FROM orders o LEFT JOIN terms t ON t.coupon_id=o.coupon_id
WHERE (o.coupon_id IS NULL AND o.discount<>0)
 OR (t.coupon_id IS NOT NULL AND o.discount::numeric<>floor(o.subtotal::numeric*t.discount_percent/100))
UNION ALL
SELECT 'INV-COUPON-EXPIRED','orders',o.id::text,jsonb_build_object('businessTime',o.created_at,'expiresAt',t.expires_at)
FROM orders o JOIN terms t ON t.coupon_id=o.coupon_id
WHERE o.created_at>=t.expires_at
UNION ALL
SELECT 'INV-COUPON-LIMIT','coupons',c.id::text,jsonb_build_object('used',c.used_count,'max',t.max_uses)
FROM coupons c JOIN terms t ON t.coupon_id=c.id WHERE c.used_count<0 OR c.used_count>t.max_uses
UNION ALL
SELECT 'ORACLE-REFERENCE-MISSING','coupons',c.id::text,'{"reference":"baseline counter"}'::jsonb
FROM coupons c LEFT JOIN counters b ON b.coupon_id=c.id WHERE b.coupon_id IS NULL
UNION ALL
SELECT 'INV-COUPON-COUNT','coupons',c.id::text,
       jsonb_build_object('actual',c.used_count,'expected',b.used_count +
         (SELECT count(*) FROM orders r WHERE r.coupon_id=c.id
          AND NOT EXISTS (SELECT 1 FROM baseline_orders old WHERE old.id=r.id)))
FROM coupons c JOIN counters b ON b.coupon_id=c.id
WHERE c.used_count <> b.used_count + (SELECT count(*) FROM orders r WHERE r.coupon_id=c.id
 AND NOT EXISTS (SELECT 1 FROM baseline_orders old WHERE old.id=r.id))
UNION ALL
SELECT 'INV-CART-REVISION-ONCE','carts',cart_id::text||'/'||cart_revision::text,jsonb_build_object('orders',count(*))
FROM orders GROUP BY cart_id,cart_revision HAVING count(*)>1
UNION ALL
SELECT 'INV-ORDER-OWNERSHIP','orders',o.id::text,'{}'::jsonb
FROM orders o JOIN carts c ON c.id=o.cart_id
WHERE o.user_id<>c.user_id
UNION ALL
SELECT 'INV-KEY-COMPLETE','orders',id::text,'{}'::jsonb
FROM orders WHERE response_body->>'id' IS DISTINCT FROM id::text
 OR response_body->>'userId' IS DISTINCT FROM user_id::text
 OR response_body->>'cartId' IS DISTINCT FROM cart_id::text
 OR response_body->>'cartRevision' IS DISTINCT FROM cart_revision::text
 OR response_body->>'total' IS DISTINCT FROM total::text
UNION ALL
SELECT 'INV-KEY-ONCE','orders',user_id::text||'/'||encode(idempotency_key_hash,'hex'),
       jsonb_build_object('orders',count(*))
FROM orders GROUP BY user_id,idempotency_key_hash HAVING count(*)>1
UNION ALL
SELECT 'ORACLE-FIXTURE-CHANGED','products',coalesce(p.id,c.product_id)::text,'{}'::jsonb
FROM products p FULL JOIN catalog c ON c.product_id=p.id
WHERE p.id IS NULL OR c.product_id IS NULL OR p.name IS DISTINCT FROM c.name
 OR p.description IS DISTINCT FROM c.description OR p.price IS DISTINCT FROM c.price
 OR p.published IS DISTINCT FROM c.published
UNION ALL
SELECT 'ORACLE-FIXTURE-CHANGED','coupons',coalesce(p.id,t.coupon_id)::text,'{}'::jsonb
FROM coupons p FULL JOIN terms t ON t.coupon_id=p.id
WHERE p.id IS NULL OR t.coupon_id IS NULL OR p.code IS DISTINCT FROM t.code
 OR p.discount_percent IS DISTINCT FROM t.discount_percent
 OR p.expires_at IS DISTINCT FROM t.expires_at OR p.max_uses IS DISTINCT FROM t.max_uses
$$;
REVOKE ALL ON FUNCTION check_business_invariants(jsonb) FROM PUBLIC;
COMMIT;
