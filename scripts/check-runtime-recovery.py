"""Windows local JAR outage + full Compose down/up verification (preserves volume/secrets).

Requires JDK 21 JAVA_HOME, both bootJar/runtimeProbeJar artifacts and existing secrets.
Owns ports 8080/8000/9000/18081/18082; stops Compose app/Gateway during local runs.
Always restores five Compose services. Never runs down -v, clean, repair or broken migrations.
"""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("postgres_checks", ROOT / "scripts/check-postgres.py")
PG = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PG)
START = time.monotonic()
EVENTS = []
PROCESSES = []
LOGS = []
PHASE = START


def record(label, **values):
    event = dict(label=label, elapsed=round(time.monotonic() - START, 3),
                 phaseElapsed=round(time.monotonic() - PHASE, 3), **values)
    EVENTS.append(event)
    print(json.dumps(event, ensure_ascii=False), flush=True)


def command(*args, timeout=240):
    result = subprocess.run(args, cwd=ROOT, capture_output=True, timeout=timeout)
    # Diagnostics contain commands/status, never secret values in argv.
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {args}\n"
                           + result.stderr.decode("utf-8", errors="replace"))
    return result.stdout


def compose(*args):
    return command("docker", "compose", *args)


def http(url):
    try:
        with urllib.request.urlopen(url, timeout=8) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as failure:
        return failure.code, failure.read().decode()


def wait(label, check, limit):
    begin = time.monotonic()
    deadline = begin + limit
    last = None
    while time.monotonic() < deadline:
        for process in PROCESSES:
            if process.poll() is not None:
                raise RuntimeError(f"Owned Java PID {process.pid} exited: {process.returncode}")
        try:
            last = check()
            if last and time.monotonic() <= deadline:
                record(label, waitSeconds=round(time.monotonic() - begin, 3), limit=limit)
                return
        except (OSError, ValueError, KeyError) as failure:
            last = type(failure).__name__
        time.sleep(min(1, max(0, deadline - time.monotonic())))
    raise AssertionError(f"Timeout {label} ({limit}s), last={last}")


def exact(url, status, body=None):
    actual, text = http(url)
    return actual == status and (body is None or text == body)


def health(up):
    status, body = http("http://localhost:8080/actuator/health")
    return status == (200 if up else 503) and json.loads(body)["status"] == ("UP" if up else "DOWN")


def registry(expected):
    request = urllib.request.Request("http://localhost:8761/eureka/apps/GROUP-PURCHASE",
                                     headers={"Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=8) as response:
            instances = json.load(response)["application"]["instance"]
        if isinstance(instances, dict):
            instances = [instances]
        return len(instances) == 1 and instances[0]["status"] == expected \
            and instances[0]["hostName"] == "localhost" and instances[0]["port"]["$"] == 8080
    except urllib.error.HTTPError as failure:
        if failure.code == 404:
            return False
        raise


def start_jar(role, probe):
    directory = "services/group-purchase-service" if role == "service" else "infrastructure/api-gateway"
    name = "group-purchase-service" if role == "service" else "api-gateway"
    suffix = "-runtime-probe" if probe else ""
    jar = ROOT / directory / f"build/libs/{name}-0.0.1-SNAPSHOT{suffix}.jar"
    java = Path(os.environ["JAVA_HOME"]) / "bin/java.exe"
    if not java.is_file() or not jar.is_file():
        raise RuntimeError(f"Missing JDK or artifact: {jar}")
    env = os.environ.copy()
    for key in ("SPRING_DATASOURCE_PASSWORD", "SPRING_APPLICATION_JSON", "JAVA_TOOL_OPTIONS",
                "JDK_JAVA_OPTIONS", "SPRING_CONFIG_IMPORT", "DB_SECRET_DIR"):
        env.pop(key, None)
    env.update(SPRING_PROFILES_ACTIVE="local", CONFIG_SERVER_URL="http://localhost:8888")
    if role == "service":
        env["SPRING_DATASOURCE_PASSWORD"] = (ROOT / "secrets/group_purchase_password").read_text().strip()
    log = open(ROOT / f".tmp/005-final-{role}-{'probe' if probe else 'product'}.log", "wb")
    LOGS.append(log)
    args = [str(java), "-Xmx256m"]
    if probe:
        args += [f"-Dprobe.role={role}", f"-Dprobe.port={18081 if role == 'service' else 18082}"]
    args += ["-jar", str(jar)]
    process = subprocess.Popen(args, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
    PROCESSES.append(process)
    # Password exists only in this child env copy, never the parent environment/argv.
    env.pop("SPRING_DATASOURCE_PASSWORD", None)
    record("started local JAR", role=role, probe=probe, pid=process.pid)


def stop_jars():
    failures = []
    for process in reversed(PROCESSES):
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
        if process.poll() is None:
            failures.append(process.pid)
    PROCESSES.clear()
    for log in LOGS:
        log.close()
    LOGS.clear()
    if failures:
        raise RuntimeError(f"Cannot stop owned Java processes: {failures}")
    record("owned local processes stopped; child secret environment released")


def sql_text(query):
    return PG.client(PG.sql("group_purchase", query), capture_output=True).stdout.strip()


def history():
    return sql_text("SELECT coalesce(json_agg(h ORDER BY installed_rank)::text, '[]') "
                    "FROM app.flyway_schema_history h")


def secret_hashes():
    return {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
            for path in (ROOT / "secrets").iterdir() if path.is_file()}


def caches(up):
    expected = "localhost:8080" if up else ""
    for role, port in (("service", 18081), ("gateway", 18082)):
        wait(f"{role} DiscoveryClient {'contains' if up else 'excludes'} target",
             lambda port=port: exact(f"http://127.0.0.1:{port}/discovery", 200, expected), 180)
    wait(f"Gateway LoadBalancer supplier count {'1' if up else '0'}",
         lambda: exact("http://127.0.0.1:18082/supplier", 200, f"count={1 if up else 0}"), 180)


def assert_identity(before):
    for port, identity in before.items():
        status, after = http(f"http://127.0.0.1:{port}/identity")
        assert status == 200 and after == identity, (identity, after)
    record("same process/context identities; refresh/close unchanged", identities=before)


def gateway():
    command("python", "scripts/check-gateway.py", "--timeout", "180")
    record("Gateway forwarding and private path/method regression passed")


def run():
    global PHASE
    snapshot = secret_hashes()
    parent_env = dict(os.environ)
    schema = "probe_runtime_" + secrets.token_hex(8)
    token = secrets.token_hex(24)
    probe_created = False
    compose_restored = False
    before_history = None
    failure = None
    cleanup_errors = []
    # Leave the exact owned schema available even if the terminal is forcibly killed.
    (ROOT / ".tmp/005-final-runtime.json").write_text(json.dumps(
        dict(passed=False, inProgress=True, runnerPid=os.getpid(), probeSchema=schema)), encoding="utf-8")
    try:
        compose("stop", "group-purchase-service", "api-gateway")
        compose("up", "-d", "--wait", "--wait-timeout", "180", "postgres", "config-server", "eureka-server")
        # An empty registry avoids old docker registrations during the local profile switch.
        compose("restart", "eureka-server")
        compose("up", "-d", "--wait", "--wait-timeout", "180", "eureka-server")
        start_jar("service", True)
        start_jar("gateway", True)
        wait("local service health UP", lambda: health(True), 120)
        wait("local Gateway health UP", lambda: exact("http://localhost:9000/actuator/health", 200), 120)
        wait("probe readiness", lambda: exact("http://127.0.0.1:18081/identity", 200)
             and exact("http://127.0.0.1:18082/identity", 200), 60)
        wait("Eureka local UP", lambda: registry("UP"), 180)
        caches(True)
        gateway()
        identities = {port: http(f"http://127.0.0.1:{port}/identity")[1] for port in (18081, 18082)}
        assert all("refresh=1;close=0;active=true" in value for value in identities.values()), identities
        before_sql = http("http://127.0.0.1:18081/sql")
        assert before_sql[0] == 200 and before_sql[1].startswith("group_purchase_app|group_purchase|app|")
        before_history = history()
        record("baseline app DataSource SQL", sql=before_sql[1], identities=identities,
               historySha256=hashlib.sha256(before_history).hexdigest())
        compose("stop", "-t", "10", "postgres")
        PHASE = time.monotonic()
        record("DB stop completed; minimum outage 120s")
        wait("app health DOWN/503", lambda: health(False), 60)
        wait("app DataSource SQL rejected", lambda: exact("http://127.0.0.1:18081/sql", 503), 60)
        assert exact("http://localhost:8080/api/group-purchases/ping", 200)
        record("direct ping still 200 (does not query DB)")
        wait("Eureka DOWN", lambda: registry("DOWN"), 180)
        caches(False)
        record("outage Gateway observation (no status contract)", status=http(
            "http://localhost:8000/api/group-purchases/ping")[0])
        while time.monotonic() - PHASE < 120:
            assert health(False)
            assert_identity(identities)
            time.sleep(min(10, max(0, 120 - (time.monotonic() - PHASE))))
        assert_identity(identities)
        record("outage maintained", outageSeconds=round(time.monotonic() - PHASE, 3))
        PHASE = time.monotonic()
        compose("up", "-d", "--no-deps", "--wait", "--wait-timeout", "120", "postgres")
        record("DB healthy", limit=120)
        wait("same app health UP/200", lambda: health(True), 60)
        wait("same app DataSource SQL recovered", lambda: exact("http://127.0.0.1:18081/sql", 200), 60)
        after_sql = http("http://127.0.0.1:18081/sql")[1]
        assert after_sql.startswith("group_purchase_app|group_purchase|app|")
        # DB stop invalidated every pre-outage physical session. Compare server epoch too:
        # backend PID alone can be recycled when the postmaster restarts.
        assert after_sql.split("|")[4] != before_sql[1].split("|")[4]
        assert_identity(identities)
        record("new physical SQL session from app DataSource", before=before_sql[1], after=after_sql)
        wait("Eureka recovered UP", lambda: registry("UP"), 180)
        caches(True)
        gateway()
        assert history() == before_history
        stop_jars()
        # Also exercise the uninstrumented product bootJars on Windows with the same env injection.
        start_jar("service", False)
        start_jar("gateway", False)
        wait("product local JAR health UP", lambda: health(True), 120)
        wait("product local Gateway health UP", lambda: exact("http://localhost:9000/actuator/health", 200), 120)
        wait("product local Eureka UP", lambda: registry("UP"), 180)
        gateway()
        for url in ("http://localhost:8080/actuator/info", "http://localhost:9000/actuator/info"):
            status, body = http(url)
            assert status == 200 and json.loads(body)["configuration"]["environment"] == "local", body
        assert history() == before_history
        record("product local profiles, Flyway history, registration and routing passed")
        stop_jars()
        probe_created = True  # Also clean up if CREATE/INSERT is only partially executed.
        PG.client(PG.sql("group_purchase", f"CREATE SCHEMA {schema}; CREATE TABLE {schema}.probe(token text); "
                        f"INSERT INTO {schema}.probe VALUES ('{token}')"), capture_output=True)
        PG.permissions("application")
        before_history = history()
        volumes_before = json.loads(command("docker", "volume", "inspect", "group-purchase_postgres-data"))
        PHASE = time.monotonic()
        compose("down")
        record("full Compose down completed (no volume deletion)")
        compose("up", "-d", "--wait", "--wait-timeout", "180")
        compose_restored = True
        record("full Compose up all five healthy", limit=180)
        assert sql_text(f"SELECT token FROM {schema}.probe").decode() == token
        assert history() == before_history
        volumes_after = json.loads(command("docker", "volume", "inspect", "group-purchase_postgres-data"))
        assert volumes_after == volumes_before
        PG.permissions("application")
        record("probe, full Flyway history, volume identity and account boundaries preserved")
        command("python", "scripts/check-discovery.py", "--timeout", "180")
        gateway()
        record("Compose docker discovery reachable from Config and Gateway passed")
    except BaseException as error:
        failure = error
        record("FAILED", error=repr(error))
    finally:
        try:
            stop_jars()
        except Exception as error:
            cleanup_errors.append(str(error))
        try:
            if not compose_restored:
                # TerminateProcess on Windows cannot guarantee Eureka deregistration.
                # Reset the registry on early failure, then let each running client converge.
                compose("up", "-d", "--wait", "--wait-timeout", "180", "postgres", "config-server", "eureka-server")
                compose("restart", "eureka-server")
            compose("up", "-d", "--wait", "--wait-timeout", "180")
            if probe_created:
                PG.client(PG.sql("group_purchase", f"DROP SCHEMA IF EXISTS {schema} CASCADE"), capture_output=True)
                assert sql_text(f"SELECT count(*) FROM pg_namespace WHERE nspname='{schema}'") == b"0"
            assert secret_hashes() == snapshot
            assert dict(os.environ) == parent_env
            command("python", "scripts/check-discovery.py", "--timeout", "180")
            command("python", "scripts/check-gateway.py", "--timeout", "180")
            values = [(ROOT / "secrets" / name).read_bytes().strip() for name in snapshot]
            for path in (ROOT / ".tmp").glob("005-final-*.log"):
                content = path.read_bytes()
                assert all(value not in content for value in values if value), f"secret in {path.name}"
            record("cleanup complete: probe absent, five Compose services healthy, secrets/env unchanged")
        except Exception as error:
            cleanup_errors.append(str(error))
        output = dict(passed=failure is None and not cleanup_errors, events=EVENTS,
                      cleanupErrors=cleanup_errors, probeSchema=schema,
                      secretHashes=snapshot, totalSeconds=round(time.monotonic() - START, 3))
        (ROOT / ".tmp/005-final-runtime.json").write_text(json.dumps(output, indent=2), encoding="utf-8")
    if failure or cleanup_errors:
        raise SystemExit(f"FAIL: {failure}; cleanup errors={cleanup_errors}")
    record("PASS all requested runtime checks")


if __name__ == "__main__":
    (ROOT / ".tmp").mkdir(exist_ok=True)
    run()
