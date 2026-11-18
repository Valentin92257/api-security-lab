\getenv detector_password LAB_DETECTOR_DB_PASSWORD
\getenv migration_password LAB_DETECTOR_MIGRATION_PASSWORD
SELECT format('CREATE ROLE detector_app LOGIN NOINHERIT PASSWORD %L', :'detector_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'detector_app')
\gexec
SELECT format('CREATE ROLE detector_migrator LOGIN NOINHERIT PASSWORD %L', :'migration_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'detector_migrator')
\gexec
SELECT 'CREATE DATABASE lab_detector OWNER detector_migrator'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'lab_detector')
\gexec
\connect lab_detector
REVOKE ALL ON DATABASE lab_detector FROM PUBLIC;
GRANT CONNECT ON DATABASE lab_detector TO detector_app, detector_migrator;
GRANT CREATE ON DATABASE lab_detector TO detector_migrator;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
