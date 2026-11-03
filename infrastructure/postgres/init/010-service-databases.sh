#!/bin/sh
# Sourced by the official entrypoint when not executable; never enable xtrace.
set -eu

# Validate every secret before creating any service role/database.
for db in postgres member group_purchase payment notification; do
    file="/run/secrets/${db}_password"
    [ -f "$file" ] && [ -s "$file" ] || { echo "Missing/empty secret: $db" >&2; exit 1; }
    value=$(cat "$file")
    case "$value" in
        ''|*[![:print:]]*) echo "Secret must be one nonempty printable line: $db" >&2; exit 1 ;;
    esac
done
unset value

psql -X -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<'SQL'
REVOKE ALL ON DATABASE postgres FROM PUBLIC;
REVOKE ALL ON DATABASE template1 FROM PUBLIC;
SQL

for db in member group_purchase payment notification; do
    export SERVICE_PASSWORD="$(cat "/run/secrets/${db}_password")"
    psql -X -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres -v db="$db" -v role="${db}_app" <<'SQL'
\getenv password SERVICE_PASSWORD
CREATE ROLE :"role" LOGIN PASSWORD :'password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
CREATE DATABASE :"db" OWNER :"role";
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT, CREATE, TEMPORARY ON DATABASE :"db" TO :"role";
SQL
    unset SERVICE_PASSWORD
    psql -X -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$db" <<'SQL'
REVOKE ALL ON SCHEMA public FROM PUBLIC;
SQL
done
