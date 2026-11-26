-- Grant fixture preparation and invariant check permissions to fixture_admin when present.
DO
$$
    BEGIN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fixture_admin') THEN
            GRANT USAGE ON SCHEMA public TO fixture_admin;
            GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON TABLE
                public.users, public.products, public.coupons, public.carts,
                public.cart_items, public.orders, public.order_items TO fixture_admin;
            GRANT USAGE, SELECT, UPDATE ON SEQUENCE
                public.users_id_seq, public.products_id_seq, public.coupons_id_seq,
                public.carts_id_seq, public.orders_id_seq TO fixture_admin;
            GRANT EXECUTE ON FUNCTION public.check_business_invariants(jsonb) TO fixture_admin;
        END IF;
    END
$$;
