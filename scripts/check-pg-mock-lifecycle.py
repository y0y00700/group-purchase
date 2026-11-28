"""014 local mandatory lifecycle matrix. Only group-purchase-014 is allowed.

Requires running dedicated full Compose, JAVA_HOME=JDK21, built product/crash JARs.
Stops/restarts only this project's services and owned child JVMs. Preserves volumes,
topics, transactions, and diagnostics. Does not run against a general project.
"""
import argparse
import concurrent.futures
from contextlib import contextmanager
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shlex
import socket
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("pg_checks", ROOT / "scripts/check-pg-mock.py")
checks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checks)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True, choices=["group-purchase-014"])
    parser.add_argument("--output", type=Path, default=ROOT / ".tmp/014-baseline/lifecycle.json")
    args = parser.parse_args()
    directory = args.output.parent / ("lifecycle-" + uuid.uuid4().hex[:8])
    directory.mkdir(parents=True)
    records = []
    compose_command = ["docker", "compose", "-p", args.project, "-f", "compose.yml", "-f", "infrastructure/pg-mock/compose-verification.yml"]

    def compose(*arguments):
        result = subprocess.run([*compose_command, *arguments], cwd=ROOT, capture_output=True, timeout=300)
        if result.returncode:
            (directory / "compose-failure.log").write_bytes(result.stdout + result.stderr)
            raise AssertionError("Compose failed; inspect " + str(directory / "compose-failure.log"))
        return result.stdout.decode("utf-8").strip()

    def passed(name, **evidence):
        records.append({"check": name, **evidence})
        print("PASS", name, flush=True)
        args.output.write_text(json.dumps({"checks": records, "diagnostics": str(directory)}, indent=2), encoding="utf-8")

    def free_port():
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            return sock.getsockname()[1]

    def secret(name):
        return (ROOT / "secrets" / name).read_text().strip()

    @contextmanager
    def jar(probe=False, phase=None, kind=None, signal=None, control=True):
        port = free_port()
        filename = "pg-mock-0.0.1-SNAPSHOT" + ("-crash-probe" if probe else "") + ".jar"
        artifact = ROOT / "infrastructure/pg-mock/build/libs" / filename
        java = Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")
        environment = os.environ.copy()
        environment.update(SPRING_PROFILES_ACTIVE="verification", SERVER_PORT=str(port), SPRING_DATASOURCE_URL=f"jdbc:postgresql://127.0.0.1:{db_port}/pg_mock?connectTimeout=1&socketTimeout=3",
                           SPRING_DATASOURCE_USERNAME="mock_pg_app", SPRING_DATASOURCE_PASSWORD=secret("pg_mock_password"),
                           APP_AUTH_API_CREDENTIAL=secret("pg_mock_api_credential"), APP_MOCK_CONTROL_ENABLED=str(control).lower(),
                           APP_RECOVERY_INITIAL_DELAY_MS="600000" if probe else "500")
        environment.pop("APP_AUTH_CONTROL_CREDENTIAL", None)
        if control:
            environment["APP_AUTH_CONTROL_CREDENTIAL"] = secret("pg_mock_control_credential")
        if probe:
            environment.update(PROBE_PHASE=phase, PROBE_KIND=kind, PROBE_SIGNAL=str(signal))
        log_path = directory / (filename + "-" + str(port) + ".log")
        with log_path.open("wb") as log:
            child = subprocess.Popen([str(java), "-jar", str(artifact)], cwd=ROOT, env=environment, stdout=log, stderr=subprocess.STDOUT,
                                     creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            client = checks.PgClient(f"http://127.0.0.1:{port}")
            try:
                checks.wait(lambda: child.poll() is None and client.request("GET", "/actuator/health/readiness", credential=False)[0] == 200,
                            timeout=60, label="owned JAR readiness: " + str(log_path))
                yield child, client
            finally:
                if child.poll() is None:
                    child.terminate()
                try:
                    child.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    child.kill(); child.wait(timeout=5)

    def sql(query):
        command = 'export PGPASSWORD="$(cat /run/secrets/pg_mock_password)"\npsql -X -w -h 127.0.0.1 -U mock_pg_app -d pg_mock -At -v ON_ERROR_STOP=1 -c ' + shlex.quote(query)
        result = subprocess.run([*compose_command, "exec", "-T", "pg-mock-postgres", "sh", "-s"], input=command.encode(), capture_output=True, cwd=ROOT, timeout=10)
        if result.returncode:
            raise AssertionError("PG SQL assertion failed (credential/message redacted)")
        return result.stdout.decode().strip()

    db_port = int(compose("port", "pg-mock-postgres", "5432").rsplit(":", 1)[1])
    pg = checks.PgClient("http://127.0.0.1:" + compose("port", "pg-mock", "8082").rsplit(":", 1)[1])
    stable, refund = str(uuid.uuid4()), str(uuid.uuid4())
    assert pg.post(stable)[0] == 200 and pg.post(stable, refund)[0] == 200
    snapshot = pg.reconcile(stable)
    phases = ["accept-before-commit", "accept-after-commit", "complete-before-commit", "complete-after-commit"]
    cases = [(kind, phase, str(uuid.uuid4()), str(uuid.uuid4()) if kind == "REFUND" else None) for kind in ("APPROVAL", "REFUND") for phase in phases]
    for kind, phase, payment, ref in cases:
        if ref:
            assert pg.post(payment)[0] == 200
    disabled_pending = str(uuid.uuid4())
    assert pg.scenario(disabled_pending, processing=6000)[0] == 200
    assert pg.post(disabled_pending)[0] == 202
    persistent_failure = str(uuid.uuid4())
    assert pg.scenario(persistent_failure, failures=2)[0] == 200
    assert pg.post(persistent_failure)[0] == 503
    failed_snapshot = pg.get(persistent_failure)[2]
    try:
        compose("stop", "pg-mock")
        with jar(control=False) as (child, product):
            assert product.request("PUT", "/__admin/v1/scenarios/APPROVAL/" + str(uuid.uuid4()), {}, credential=False)[0] == 404
            assert product.get(stable)[2]["status"] == "SUCCEEDED"
            assert product.get(persistent_failure)[2] == failed_snapshot
            assert product.post(persistent_failure)[0] == 503
            assert product.post(persistent_failure)[0] == 200
            assert len(sql(f"SELECT attempt FROM pg_execution_attempt WHERE operation_id=(SELECT operation_id FROM pg_operation WHERE business_id='{persistent_failure}')").splitlines()) == 3
            checks.wait(lambda: product.get(disabled_pending)[2]["status"] == "SUCCEEDED", timeout=20, label="bound operation with control disabled")
            assert len(sql(f"SELECT operation_id FROM pg_ledger WHERE operation_id=(SELECT operation_id FROM pg_operation WHERE business_id='{disabled_pending}')")) > 0
            passed("Windows product JAR independent boot and control disabled without control secret", pid=child.pid)
        with jar() as (child, product):
            subprocess.run([sys.executable, str(ROOT / "scripts/check-pg-mock.py"), "--base", product.base, "--output", str(directory / "windows-smoke.json")], cwd=ROOT, check=True, timeout=90)
            assert product.reconcile(stable) == snapshot
            compose("stop", "pg-mock-postgres")
            checks.wait(lambda: product.request("GET", "/actuator/health/readiness", credential=False)[0] == 503, label="same JAR readiness DOWN")
            assert child.poll() is None
            assert product.request("GET", "/actuator/health/liveness", credential=False)[0] == 200
            unavailable = product.get(stable)
            assert unavailable[0] == 503 and unavailable[2]["code"] == "PG_UNAVAILABLE"
            compose("start", "pg-mock-postgres")
            checks.wait(lambda: product.request("GET", "/actuator/health/readiness", credential=False)[0] == 200, timeout=60, label="same JAR readiness UP")
            assert child.poll() is None and product.reconcile(stable) == snapshot
            passed("same product JVM PG DB outage DOWN to UP and immutable ledger", pid=child.pid)
        for kind, phase, payment, ref in cases:
            business = ref or payment
            signal = directory / (kind + "-" + phase + ".signal")
            with jar(probe=True, phase=phase, kind=kind, signal=signal) as (child, probe_client):
                with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
                    pending = executor.submit(probe_client.post, payment, ref)
                    checks.wait(signal.exists, timeout=10, label=kind + " " + phase)
                    assert signal.read_text() == phase
                    child.kill(); child.wait(timeout=5)
                    try:
                        pending.result(timeout=5)
                        raise AssertionError("Killed request unexpectedly returned normally")
                    except (OSError, checks.http.client.HTTPException):
                        pass
            state = sql(f"SELECT json_build_object('status',o.status,'ledger',(SELECT count(*) FROM pg_ledger l WHERE l.operation_id=o.operation_id),'attempts',(SELECT count(*) FROM pg_execution_attempt a WHERE a.operation_id=o.operation_id),'result',(SELECT result FROM pg_execution_attempt a WHERE a.operation_id=o.operation_id AND a.attempt=o.attempt)) FROM pg_operation o WHERE kind='{kind}' AND business_id='{business}'")
            before = json.loads(state) if state else None
            if phase == "accept-before-commit":
                assert before is None, before
            else:
                succeeded = phase == "complete-after-commit"
                assert before == {"status": "SUCCEEDED" if succeeded else "PENDING", "ledger": int(succeeded), "attempts": 1, "result": "SUCCEEDED" if succeeded else "PENDING"}, before
            with jar() as (_, product):
                if before is None:
                    assert product.post(payment, ref)[0] == 200
                checks.wait(lambda: product.get(payment, ref)[2]["status"] == "SUCCEEDED", label="crash recovery " + phase)
                final = product.get(payment, ref)[2]
                assert product.post(payment, ref)[2] == final
                data = product.reconcile(payment)
                own = [item for item in data["ledger"] if str(item["operation_id"]) == final["operationId"]]
                assert len(own) == 1
            passed(kind + " forced JVM termination " + phase, businessId=business, beforeRecovery=before)
        compose("up", "-d", "--wait", "--wait-timeout", "180", "pg-mock")
        assert pg.reconcile(stable) == snapshot
        compose("stop", "postgres")
        independent = str(uuid.uuid4())
        assert pg.post(independent)[0] == 200 and pg.get(independent)[2]["status"] == "SUCCEEDED"
        assert pg.reconcile(stable) == snapshot
        compose("start", "postgres")
        for port in (8080, 8081):
            client = checks.PgClient("http://127.0.0.1:" + str(port))
            checks.wait(lambda: client.request("GET", "/actuator/health/readiness", credential=False)[0] == 200, timeout=180, label="business DB route readiness")
        gateway = checks.PgClient("http://127.0.0.1:8000")
        checks.wait(lambda: gateway.request("GET", "/api/group-purchases/ping", credential=False)[0] == 200, timeout=180, label="Gateway purchase route recovered")
        checks.wait(lambda: gateway.request("GET", "/api/members/me", credential=False)[0] == 401, timeout=180, label="Gateway member auth recovered")
        passed("business DB outage isolated from PG and Gateway routes recovered", paymentAttemptId=independent)
        permissions = sql("SELECT NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user")
        assert permissions == "t"
        assert sql("SELECT NOT has_database_privilege(current_user,'postgres','CONNECT') AND NOT has_database_privilege(current_user,'template1','CONNECT')") == "t"
        passed("restricted PG app role and denied other databases")
        compose("down")
        compose("up", "-d", "--wait", "--wait-timeout", "240")
        assert pg.reconcile(stable) == snapshot
        for kind, phase, payment, ref in cases:
            assert pg.get(payment, ref)[2]["status"] == "SUCCEEDED"
        assert sql("SELECT count(*) FROM pg_operation o LEFT JOIN pg_ledger l USING(operation_id) WHERE (o.status='SUCCEEDED')<>(l.operation_id IS NOT NULL) OR o.completed_at IS DISTINCT FROM l.occurred_at") == "0"
        assert sql("SELECT count(*) FROM pg_execution_attempt a JOIN pg_operation o USING(operation_id) WHERE a.attempt=o.attempt AND a.result<>o.status") == "0"
        passed("full dedicated Compose down/up keeps all operation ledger time key scenario attempt records", stablePayment=stable)
        artifacts = {}
        for name in ("pg-mock-0.0.1-SNAPSHOT.jar", "pg-mock-0.0.1-SNAPSHOT-crash-probe.jar"):
            artifacts[name] = hashlib.sha256((ROOT / "infrastructure/pg-mock/build/libs" / name).read_bytes()).hexdigest()
        passed("artifact identities", sha256=artifacts)
    finally:
        # Restore owned services on failure; caller performs final down without -v.
        compose("start", "pg-mock-postgres", "postgres")
        compose("up", "-d", "--wait", "--wait-timeout", "180", "pg-mock")
    print(f"PASS {len(records)} lifecycle groups; evidence={args.output}", flush=True)


if __name__ == "__main__":
    main()
