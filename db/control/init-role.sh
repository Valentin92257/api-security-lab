#!/bin/sh
set -eu
psql --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    --set=control_password="$LAB_CONTROL_DB_PASSWORD" <<'SQL'
CREATE ROLE control_app LOGIN PASSWORD :'control_password';
SQL
