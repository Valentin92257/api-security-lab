-- Install in each shop DB after shop-schema.sql. Read-only functions; no fixture writes.
BEGIN;
CREATE FUNCTION check_business_invariants(baseline jsonb)
RETURNS TABLE (invariant_id text, entity text, entity_id text, details jsonb)
LANGUAGE sql STABLE AS $$
WITH catalog AS (
  SELECT * FROM jsonb_to_recordset(baseline->'catalogVersions')
    AS x(product_id bigint, catalog_version bigint, price bigint, published boolean)
), terms AS (
  SELECT * FROM jsonb_to_recordset(baseline->'couponVersions')
    AS x(coupon_id bigint, terms_version bigint, discount_percent integer, expires_at timestamptz)
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
       jsonb_build_object('catalogVersion',oi.catalog_version)
FROM order_items oi LEFT JOIN catalog c ON c.product_id=oi.product_id AND c.catalog_version=oi.catalog_version
WHERE c.product_id IS NULL
UNION ALL
SELECT 'INV-ORDER-PRICE','order_items',oi.order_id::text||'/'||oi.product_id::text,
       jsonb_build_object('expected',c.price,'actual',oi.unit_price)
FROM order_items oi JOIN catalog c ON c.product_id=oi.product_id AND c.catalog_version=oi.catalog_version
WHERE oi.unit_price<>c.price OR NOT c.published
UNION ALL
SELECT 'ORACLE-REFERENCE-MISSING','orders',o.id::text,jsonb_build_object('couponVersion',o.coupon_terms_version)
FROM orders o LEFT JOIN terms t ON t.coupon_id=o.coupon_id AND t.terms_version=o.coupon_terms_version
WHERE o.coupon_id IS NOT NULL AND t.coupon_id IS NULL
UNION ALL
SELECT 'INV-ORDER-DISCOUNT','orders',o.id::text,jsonb_build_object('actual',o.discount)
FROM orders o LEFT JOIN terms t ON t.coupon_id=o.coupon_id AND t.terms_version=o.coupon_terms_version
WHERE (o.coupon_id IS NULL AND (o.discount<>0 OR o.coupon_code IS NOT NULL OR o.coupon_percent IS NOT NULL
 OR o.coupon_expires_at IS NOT NULL OR o.coupon_terms_version IS NOT NULL))
 OR (t.coupon_id IS NOT NULL AND (o.discount::numeric<>floor(o.subtotal::numeric*t.discount_percent/100)
 OR o.coupon_percent IS DISTINCT FROM t.discount_percent OR o.coupon_expires_at IS DISTINCT FROM t.expires_at))
UNION ALL
SELECT 'INV-COUPON-EXPIRED','orders',o.id::text,jsonb_build_object('businessTime',o.created_at,'expiresAt',t.expires_at)
FROM orders o JOIN terms t ON t.coupon_id=o.coupon_id AND t.terms_version=o.coupon_terms_version
WHERE o.created_at>=t.expires_at
UNION ALL
SELECT 'INV-COUPON-LIMIT','coupons',id::text,jsonb_build_object('used',used_count,'max',max_uses)
FROM coupons WHERE used_count<0 OR used_count>max_uses
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
SELECT 'INV-INTENT-ONCE','checkout_intents',intent_id::text,jsonb_build_object('orders',count(*))
FROM orders GROUP BY intent_id HAVING count(*)>1
UNION ALL
SELECT 'INV-CART-REVISION-ONCE','carts',cart_id::text||'/'||cart_revision::text,jsonb_build_object('orders',count(*))
FROM orders GROUP BY cart_id,cart_revision HAVING count(*)>1
UNION ALL
SELECT 'INV-ORDER-OWNERSHIP','orders',o.id::text,'{}'::jsonb
FROM orders o JOIN checkout_intents i ON i.id=o.intent_id JOIN carts c ON c.id=o.cart_id
WHERE o.user_id<>i.user_id OR o.user_id<>c.user_id OR o.cart_id<>i.cart_id OR o.cart_revision<>i.cart_revision
UNION ALL
SELECT 'INV-INTENT-CONSUMED','orders',o.id::text,'{}'::jsonb
FROM orders o JOIN checkout_intents i ON i.id=o.intent_id WHERE i.consumed_order_id IS DISTINCT FROM o.id
UNION ALL
SELECT 'INV-KEY-COMPLETE','orders',id::text,'{}'::jsonb
FROM orders WHERE response_body->>'id' IS DISTINCT FROM id::text
 OR response_body->>'userId' IS DISTINCT FROM user_id::text
 OR response_body->>'intentId' IS DISTINCT FROM intent_id::text
 OR response_body->>'total' IS DISTINCT FROM total::text
UNION ALL
SELECT 'INV-KEY-ONCE','orders',user_id::text||'/'||encode(idempotency_key_hash,'hex'),
       jsonb_build_object('orders',count(*))
FROM orders GROUP BY user_id,idempotency_key_hash HAVING count(*)>1
$$;
REVOKE ALL ON FUNCTION check_business_invariants(jsonb) FROM PUBLIC;
COMMIT;
