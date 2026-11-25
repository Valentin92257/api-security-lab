-- Grant fixture preparation and invariant check permissions to fixture_admin when present.
DO
$$
    BEGIN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fixture_admin') THEN
            GRANT USAGE ON SCHEMA public TO fixture_admin;
            GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON TABLE
                users, products, coupons, carts, cart_items, orders, order_items TO fixture_admin;
            GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO fixture_admin;
            GRANT EXECUTE ON FUNCTION check_business_invariants(jsonb) TO fixture_admin;
        END IF;
    END
$$;
