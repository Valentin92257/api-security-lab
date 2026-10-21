-- Only shop_protected, after shop-schema.sql.
BEGIN;
ALTER TABLE cart_items ADD CONSTRAINT cart_items_protected_1 CHECK (quantity BETWEEN 1 AND 99);
ALTER TABLE order_items ADD CONSTRAINT order_items_protected_1 CHECK (quantity BETWEEN 1 AND 99);
ALTER TABLE order_items ADD CONSTRAINT order_items_protected_2 CHECK (unit_price BETWEEN 1 AND 1000000000);
ALTER TABLE order_items ADD CONSTRAINT order_items_protected_3 CHECK (catalog_version > 0);
ALTER TABLE coupons ADD CONSTRAINT coupons_protected_1 CHECK (used_count BETWEEN 0 AND max_uses);
ALTER TABLE orders ADD CONSTRAINT orders_protected_1 CHECK (subtotal BETWEEN 0 AND 9900000000000);
ALTER TABLE orders ADD CONSTRAINT orders_protected_2 CHECK (discount BETWEEN 0 AND subtotal);
ALTER TABLE orders ADD CONSTRAINT orders_protected_3 CHECK (total = subtotal - discount);
ALTER TABLE orders ADD CONSTRAINT orders_protected_4 CHECK ((coupon_id IS NULL AND coupon_code IS NULL AND coupon_percent IS NULL AND coupon_expires_at IS NULL AND coupon_terms_version IS NULL AND discount = 0) OR (coupon_id IS NOT NULL AND coupon_code IS NOT NULL AND coupon_percent IS NOT NULL AND coupon_percent BETWEEN 1 AND 100 AND coupon_expires_at IS NOT NULL AND coupon_terms_version IS NOT NULL AND coupon_terms_version > 0 AND created_at < coupon_expires_at));
ALTER TABLE orders ADD CONSTRAINT orders_intent_once UNIQUE (intent_id);
ALTER TABLE orders ADD CONSTRAINT orders_cart_revision_once UNIQUE (cart_id, cart_revision);
ALTER TABLE orders ADD CONSTRAINT orders_idempotency_scope_once UNIQUE (user_id, idempotency_key_hash);
COMMIT;
