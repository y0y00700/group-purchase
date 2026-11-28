#!/bin/sh
set -eu
file=/run/secrets/pg_mock_password
[ -s "$file" ] || { echo 'Missing PG app password' >&2; exit 1; }
export PG_APP_PASSWORD="$(cat "$file")"
case "$PG_APP_PASSWORD" in ''|*[![:print:]]*) echo 'Invalid PG app password' >&2; exit 1 ;; esac
psql -X -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<'SQL'
\getenv password PG_APP_PASSWORD
CREATE ROLE mock_pg_app LOGIN PASSWORD :'password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
CREATE DATABASE pg_mock OWNER mock_pg_app;
REVOKE ALL ON DATABASE postgres FROM PUBLIC;
REVOKE ALL ON DATABASE template1 FROM PUBLIC;
REVOKE ALL ON DATABASE pg_mock FROM PUBLIC;
GRANT CONNECT, CREATE, TEMPORARY ON DATABASE pg_mock TO mock_pg_app;
SQL
unset PG_APP_PASSWORD
psql -X -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname pg_mock <<'SQL'
REVOKE ALL ON SCHEMA public FROM PUBLIC;
SQL
