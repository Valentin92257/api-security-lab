-- Only shop_protected, after shop-schema.sql.
BEGIN;
ALTER TABLE cart_items ADD CONSTRAINT cart_items_protected_1 CHECK (quantity BETWEEN 1 AND 99);
ALTER TABLE order_items ADD CONSTRAINT order_items_protected_1 CHECK (quantity BETWEEN 1 AND 99);
ALTER TABLE order_items ADD CONSTRAINT order_items_protected_2 CHECK (unit_price BETWEEN 1 AND 1000000000);
ALTER TABLE coupons ADD CONSTRAINT coupons_protected_1 CHECK (used_count BETWEEN 0 AND max_uses);
ALTER TABLE orders ADD CONSTRAINT orders_protected_1 CHECK (subtotal BETWEEN 0 AND 9900000000000);
ALTER TABLE orders ADD CONSTRAINT orders_protected_2 CHECK (discount BETWEEN 0 AND subtotal);
ALTER TABLE orders ADD CONSTRAINT orders_protected_3 CHECK (total = subtotal - discount);
ALTER TABLE orders ADD CONSTRAINT orders_protected_4 CHECK (coupon_id IS NOT NULL OR discount = 0);
ALTER TABLE orders ADD CONSTRAINT orders_cart_revision_once UNIQUE (cart_id, cart_revision);
ALTER TABLE orders ADD CONSTRAINT orders_idempotency_scope_once UNIQUE (user_id, idempotency_key_hash);
COMMIT;
