"""Verify PostgreSQL over TCP from a separate Compose client container.

--persistence recreates only postgres, preserving the named volume, and removes
only its randomly named probe schema. COMPOSE_PROJECT_NAME/POSTGRES_PORT work.
"""

import argparse
from pathlib import Path
import secrets
import shlex
import subprocess

ROOT = Path(__file__).resolve().parents[1]
DATABASES = ("member", "group_purchase", "payment", "notification")


def compose(*args, **kwargs):
    return subprocess.run(["docker", "compose", *args], cwd=ROOT,
                          check=True, timeout=180, **kwargs)


def client(script, capture_output=False):
    # Credentials stay inside the container, never in argv or captured output.
    return compose("run", "--rm", "--no-deps", "-T", "--entrypoint", "sh", "postgres", "-s",
            input=("set -eu\nexport LC_ALL=C PGCONNECT_TIMEOUT=5\n" + script).encode("utf-8"),
            capture_output=capture_output)


def sql(db, query, admin=False):
    user = "postgres" if admin else f"{db}_app"
    secret = "postgres" if admin else db
    return (f'export PGPASSWORD="$(cat /run/secrets/{secret}_password)"\n'
            f"psql -X -w -h postgres -U {user} -d {db} -v ON_ERROR_STOP=1 -At "
            f"-c {shlex.quote(query)}")


def history():
    query = """SELECT row_to_json(h) FROM app.flyway_schema_history h
        WHERE version='1' AND success ORDER BY installed_rank"""
    return client(sql("group_purchase", query), capture_output=True).stdout.strip()


def permissions(stage):
    checks = []
    for db in DATABASES:
        checks.append(f'export PGPASSWORD="$(cat /run/secrets/{db}_password)"')
        schema_condition = "NOT EXISTS (SELECT FROM pg_namespace WHERE nspname = 'app')"
        if stage == "application" and db == "group_purchase":
            schema_condition = """EXISTS (SELECT FROM pg_namespace WHERE nspname='app'
                AND pg_get_userbyid(nspowner)=current_user)
                AND NOT EXISTS (SELECT FROM pg_namespace n, LATERAL aclexplode(n.nspacl) a
                    WHERE n.nspname='app' AND a.grantee=0)
                AND (SELECT count(*)=1 AND bool_and(success AND checksum IS NOT NULL)
                    FROM app.flyway_schema_history WHERE version='1')"""
        query = f"""SELECT current_user = '{db}_app' AND current_database() = '{db}'
            AND NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)
            AND has_database_privilege(current_user, current_database(), 'CREATE')
            AND has_database_privilege(current_user, current_database(), 'TEMPORARY')
            AND EXISTS (SELECT FROM pg_database WHERE datname=current_database()
                AND pg_get_userbyid(datdba)=current_user)
            AND NOT EXISTS (SELECT FROM pg_auth_members WHERE member = pg_roles.oid)
            AND ({schema_condition})
            AND NOT EXISTS (SELECT FROM pg_namespace n,
                LATERAL aclexplode(n.nspacl) a WHERE n.nspname='public' AND a.grantee=0)
            FROM pg_roles WHERE rolname = current_user"""
        checks.append(f'[ "$({sql(db, query)})" = t ]\necho "PASS own DB: {db}"')
        for other in (*DATABASES, "postgres", "template1"):
            if other == db:
                continue
            command = (f"psql -X -w -h postgres -U {db}_app -d {other} -c 'SELECT 1'")
            checks.append(f"""if output=$({command} 2>&1); then
    echo 'Unexpected database access: {db} -> {other}' >&2; exit 1
fi
case "$output" in
  *'permission denied for database'*) echo 'PASS denied DB: {db} -> {other}' ;;
  *) echo "Unexpected failure: $output" >&2; exit 1 ;;
esac""")
        for other in DATABASES:
            if other != db:
                command = sql(db, f"SET ROLE {other}_app")
                checks.append(f"""if output=$({command} 2>&1); then
    echo 'Unexpected SET ROLE success' >&2; exit 1
fi
case "$output" in
  *'permission denied to set role'*) : ;;
  *) echo "Unexpected failure: $output" >&2; exit 1 ;;
esac""")
        checks.append(f"""export PGPASSWORD='invalid-{secrets.token_hex(32)}'
if output=$(psql -X -w -h postgres -U {db}_app -d {db} -c 'SELECT 1' 2>&1); then
    echo 'Unexpected invalid-password success' >&2; exit 1
fi
case "$output" in
  *'password authentication failed'*) echo 'PASS invalid password: {db}' ;;
  *) echo "Unexpected failure: $output" >&2; exit 1 ;;
esac""")
    query = """SELECT count(*) = 5 AND bool_and(rolpassword LIKE 'SCRAM-SHA-256$%')
        FROM pg_authid WHERE rolname IN
        ('postgres','member_app','group_purchase_app','payment_app','notification_app')"""
    checks.append(f'[ "$({sql("postgres", query, admin=True)})" = t ]')
    client("\n".join(checks))
    print("PASS: 4 own DBs, 12 cross-DB denials, 8 system DB denials, "
          "12 SET ROLE denials, 4 invalid passwords, 5 SCRAM verifiers", flush=True)


def persistence():
    schema = "probe_" + secrets.token_hex(8)
    token = secrets.token_hex(24)
    try:
        client(sql("group_purchase", f"CREATE SCHEMA {schema}; CREATE TABLE {schema}.probe "
                   f"(token text); INSERT INTO {schema}.probe VALUES ('{token}');"))
        compose("up", "-d", "--no-deps", "--force-recreate", "--wait",
                "--wait-timeout", "120", "postgres")
        query = f"SELECT token FROM {schema}.probe"
        client(f'[ "$({sql("group_purchase", query)})" = {shlex.quote(token)} ]')
        print("PASS: probe survived PostgreSQL container recreation", flush=True)
    finally:
        client(sql("group_purchase", f"DROP SCHEMA IF EXISTS {schema} CASCADE"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", choices=("database", "application"), default="database")
    parser.add_argument("--persistence", action="store_true")
    args = parser.parse_args()
    permissions(args.stage)
    before = history() if args.stage == "application" else None
    if args.persistence:
        persistence()
        permissions(args.stage)
        if before is not None:
            if history() != before:
                raise SystemExit("Flyway V1 history changed during DB recreation")
            print("PASS: Flyway V1 checksum and installation history preserved", flush=True)


if __name__ == "__main__":
    main()
