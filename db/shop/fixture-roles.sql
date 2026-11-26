\getenv fixture_password LAB_FIXTURE_DB_PASSWORD
SELECT format('CREATE ROLE fixture_admin LOGIN NOINHERIT PASSWORD %L', :'fixture_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'fixture_admin')
\gexec

REVOKE ALL ON DATABASE lab_control FROM PUBLIC;
GRANT CONNECT ON DATABASE lab_control TO control_app;

\connect vulnerable
REVOKE ALL ON DATABASE vulnerable FROM PUBLIC;
GRANT CONNECT ON DATABASE vulnerable TO fixture_admin;
GRANT USAGE ON SCHEMA public TO fixture_admin;
DO
$$
    BEGIN
        IF to_regclass('public.users') IS NOT NULL THEN
            GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON TABLE
                public.users, public.products, public.coupons, public.carts,
                public.cart_items, public.orders, public.order_items TO fixture_admin;
            GRANT USAGE, SELECT, UPDATE ON SEQUENCE
                public.users_id_seq, public.products_id_seq, public.coupons_id_seq,
                public.carts_id_seq, public.orders_id_seq TO fixture_admin;
        END IF;
        IF to_regprocedure('public.check_business_invariants(jsonb)') IS NOT NULL THEN
            GRANT EXECUTE ON FUNCTION public.check_business_invariants(jsonb) TO fixture_admin;
        END IF;
    END
$$;

\connect protected
REVOKE ALL ON DATABASE protected FROM PUBLIC;
GRANT CONNECT ON DATABASE protected TO fixture_admin;
GRANT USAGE ON SCHEMA public TO fixture_admin;
DO
$$
    BEGIN
        IF to_regclass('public.users') IS NOT NULL THEN
            GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON TABLE
                public.users, public.products, public.coupons, public.carts,
                public.cart_items, public.orders, public.order_items TO fixture_admin;
            GRANT USAGE, SELECT, UPDATE ON SEQUENCE
                public.users_id_seq, public.products_id_seq, public.coupons_id_seq,
                public.carts_id_seq, public.orders_id_seq TO fixture_admin;
        END IF;
        IF to_regprocedure('public.check_business_invariants(jsonb)') IS NOT NULL THEN
            GRANT EXECUTE ON FUNCTION public.check_business_invariants(jsonb) TO fixture_admin;
        END IF;
    END
$$;
