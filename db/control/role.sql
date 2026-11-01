\getenv control_password LAB_CONTROL_DB_PASSWORD
SELECT format('CREATE ROLE control_app LOGIN PASSWORD %L', :'control_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'control_app')
\gexec
