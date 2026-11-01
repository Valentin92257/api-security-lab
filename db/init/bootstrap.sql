SELECT format('CREATE DATABASE %I', database_name)
FROM (VALUES ('lab_control'), ('vulnerable'), ('protected')) AS databases(database_name)
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = database_name)
\gexec
\ir ../control/role.sql
