-- Только protected: БД как последний рубеж защиты бизнес-инвариантов.

ALTER TABLE cart_items
    ADD CONSTRAINT cart_items_quantity_range
        CHECK (quantity BETWEEN 1 AND 99);

ALTER TABLE order_items
    ADD CONSTRAINT order_items_quantity_positive
        CHECK (quantity > 0);
ALTER TABLE order_items
    ADD CONSTRAINT order_items_unit_price_positive
        CHECK (unit_price > 0);

ALTER TABLE coupons
    ADD CONSTRAINT coupons_usage_within_limit
        CHECK (used_count >= 0 AND used_count <= max_uses);

ALTER TABLE orders
    ADD CONSTRAINT orders_amounts_consistent
        CHECK (subtotal >= 0
            AND discount >= 0
            AND discount <= subtotal
            AND total = subtotal - discount);