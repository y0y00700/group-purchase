"""Check Windows Java 21 product JARs on isolated ports, plus failed startup JSON/privacy.

Requires existing Compose Config/PostgreSQL, built product JARs and JAVA_HOME. Child-only secrets.
Leaves Compose running and terminates only processes started here.
"""
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("baseline", ROOT / "scripts/check-api-observability.py")
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)
JAVA = str(Path(os.environ["JAVA_HOME"]) / "bin/java.exe")
JARS = {"group-purchase": ROOT / "services/group-purchase-service/build/libs/group-purchase-service-0.0.1-SNAPSHOT.jar",
        "api-gateway": ROOT / "infrastructure/api-gateway/build/libs/api-gateway-0.0.1-SNAPSHOT.jar"}


def validate(raw):
    assert CHECK.SENTINEL not in raw
    events = [json.loads(line) for line in raw.splitlines() if line.strip()]
    assert events
    for event in events:
        assert event["environment"] == "local"
        assert "service" in event
    return events


def main():
    for jar in JARS.values():
        with zipfile.ZipFile(jar) as archive:
            assert not any("Fixture" in name or "runtimeprobe" in name or "ApiObservabilityTests" in name for name in archive.namelist())
    processes = []
    with tempfile.TemporaryDirectory(prefix="006-local-") as directory:
        logs = []
        try:
            for service, port in [("group-purchase", 18080), ("api-gateway", 18000)]:
                environment = os.environ.copy()
                environment["SPRING_PROFILES_ACTIVE"] = "local"
                environment["CONFIG_SERVER_URL"] = "http://127.0.0.1:8888"
                if service == "group-purchase":
                    environment["SPRING_DATASOURCE_PASSWORD"] = (ROOT / "secrets/group_purchase_password").read_text().rstrip("\r\n")
                else:
                    environment.pop("SPRING_DATASOURCE_PASSWORD", None)
                output = open(Path(directory) / (service + ".jsonl"), "w", encoding="utf-8")
                logs.append(output)
                args = [JAVA, "-jar", str(JARS[service]), f"--server.port={port}", "--eureka.client.enabled=false"]
                if service == "api-gateway":
                    args += ["--management.server.port=19000", "--spring.cloud.discovery.client.simple.instances.group-purchase[0].uri=http://127.0.0.1:18080"]
                processes.append(subprocess.Popen(args, cwd=ROOT, env=environment, stdout=output, stderr=subprocess.PIPE))
                CHECK.wait(lambda: CHECK.http(port, CHECK.PING)[0] == 200)
                CHECK.problem(port, "/api/missing?secret=" + CHECK.SENTINEL, 404)
            CHECK.problem(18000, CHECK.PING, 404, "POST")
            CHECK.problem(18080, CHECK.PING, 405, "POST")
            status, headers, _ = CHECK.http(18000, CHECK.PING, headers={"X-Request-Id": "a" * 32})
            identifier = headers["X-Request-Id"]
            assert status == 200 and identifier != "a" * 32
        finally:
            for process in processes:
                process.terminate()
                try:
                    _, stderr = process.communicate(timeout=20)
                except subprocess.TimeoutExpired:
                    process.kill()
                    _, stderr = process.communicate(timeout=10)
                assert CHECK.SENTINEL.encode() not in stderr
            for output in logs:
                output.close()
        for output in logs:
            events = validate(Path(output.name).read_text(encoding="utf-8"))
            assert sum(event.get("requestId") == identifier and event.get("event") == "http_request_completed" for event in events) == 1
        # A closed port causes Config failure before a context is created. No real secret is used.
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            refused = listener.getsockname()[1]
        for service, jar in JARS.items():
            environment = os.environ.copy()
            environment.update(CONFIG_SERVER_URL=f"http://127.0.0.1:{refused}", SPRING_PROFILES_ACTIVE="local",
                               SPRING_DATASOURCE_PASSWORD=CHECK.SENTINEL)
            result = subprocess.run([JAVA, "-jar", str(jar)], cwd=ROOT, env=environment,
                                    capture_output=True, timeout=45)
            assert result.returncode != 0
            assert CHECK.SENTINEL.encode() not in result.stderr
            validate(result.stdout.decode("utf-8"))
        environment = os.environ.copy()
        environment.update(CONFIG_SERVER_URL="http://127.0.0.1:8888", SPRING_PROFILES_ACTIVE="local",
                           SPRING_DATASOURCE_PASSWORD=CHECK.SENTINEL + "_BAD_DB_PASSWORD")
        result = subprocess.run([JAVA, "-jar", str(JARS["group-purchase"]),
                                 "--server.port=0", "--eureka.client.enabled=false"],
                                cwd=ROOT, env=environment, capture_output=True, timeout=60)
        assert result.returncode != 0
        assert CHECK.SENTINEL.encode() not in result.stderr
        validate(result.stdout.decode("utf-8"))
    print("PASS: Windows local product JAR contracts, ID/log linkage, isolated Config failure JSON/privacy, fixture exclusion")


if __name__ == "__main__":
    main()
