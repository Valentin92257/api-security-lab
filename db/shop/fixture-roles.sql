\getenv fixture_password LAB_FIXTURE_DB_PASSWORD
SELECT format('CREATE ROLE fixture_admin LOGIN NOINHERIT PASSWORD %L', :'fixture_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'fixture_admin')
\gexec

\connect vulnerable
REVOKE ALL ON DATABASE vulnerable FROM PUBLIC;
GRANT CONNECT ON DATABASE vulnerable TO fixture_admin;
GRANT USAGE ON SCHEMA public TO fixture_admin;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON TABLES TO fixture_admin;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO fixture_admin;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT EXECUTE ON FUNCTIONS TO fixture_admin;
DO
$$
    BEGIN
        IF to_regclass('public.users') IS NOT NULL THEN
            GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON TABLE
                users, products, coupons, carts, cart_items, orders, order_items TO fixture_admin;
            GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO fixture_admin;
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
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON TABLES TO fixture_admin;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO fixture_admin;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT EXECUTE ON FUNCTIONS TO fixture_admin;
DO
$$
    BEGIN
        IF to_regclass('public.users') IS NOT NULL THEN
            GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON TABLE
                users, products, coupons, carts, cart_items, orders, order_items TO fixture_admin;
            GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO fixture_admin;
        END IF;
        IF to_regprocedure('public.check_business_invariants(jsonb)') IS NOT NULL THEN
            GRANT EXECUTE ON FUNCTION public.check_business_invariants(jsonb) TO fixture_admin;
        END IF;
    END
$$;
