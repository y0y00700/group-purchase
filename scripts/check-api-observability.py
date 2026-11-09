"""006 real Compose contracts. Optional --db-recovery stops/starts PostgreSQL, never deletes data.

No third-party Python dependencies. Existing six healthy services and secrets required.
"""
import argparse
import concurrent.futures
import json
import math
from pathlib import Path
import re
import subprocess
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
SERVICES = {"config-server": 8888, "eureka-server": 8761,
            "group-purchase-service": 8080, "api-gateway": 9000, "member-service": 8081}
PING = "/api/group-purchases/ping"
SENTINEL = "SENTINEL_PRIVATE_OBSERVABILITY_006"


def compose(*args):
    result = subprocess.run(["docker", "compose", *args], cwd=ROOT, capture_output=True, timeout=240)
    if result.returncode:
        raise RuntimeError("Compose command failed: " + " ".join(args))
    return result.stdout.decode("utf-8")


def http(port, path, method="GET", headers=None):
    req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", method=method, headers=headers or {})
    try:
        response = urllib.request.urlopen(req, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.code, dict(response.headers), response.read().decode("utf-8")


def management(service, path):
    if service == "api-gateway":
        raw = compose("exec", "-T", service, "curl", "--silent", "--max-time", "10", "-w", "\n%{http_code}",
                      f"http://localhost:9000{path}")
        body, status = raw.rsplit("\n", 1)
        return int(status), json.loads(body)
    status, _, body = http(SERVICES[service], path)
    return status, json.loads(body)


def wait(check, timeout=180):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if check():
                return
        except (OSError, ValueError, AssertionError):
            pass
        time.sleep(1)
    raise AssertionError(f"Condition did not converge within {timeout}s")


def process_identity(container):
    result = subprocess.run(["docker", "inspect", "--format",
                             "{{.State.StartedAt}} {{.State.Pid}} {{.RestartCount}}", container],
                            cwd=ROOT, capture_output=True, check=True, timeout=15)
    return result.stdout


def health(service, expected):
    for group, (status, state) in expected.items():
        actual_status, body = management(service, "/actuator/health" + group)
        assert actual_status == status, (service, group, actual_status)
        assert body["status"] == state and set(body) <= {"status", "groups"}, (service, group, body)
        if "groups" in body:
            assert set(body["groups"]) == {"liveness", "readiness"}
    return True


def problem(port, path, status, method="GET"):
    actual, headers, raw = http(port, path, method, {"Accept": "text/html", "X-Request-Id": SENTINEL,
                                                   "Authorization": SENTINEL, "Cookie": SENTINEL})
    assert actual == status, (port, path, actual)
    assert headers["Content-Type"].startswith("application/problem+json")
    if method == "HEAD":
        assert raw == ""
        return
    body = json.loads(raw)
    assert set(body) == {"type", "title", "status", "detail", "instance", "code", "requestId"}
    assert body["type"] == "about:blank" and body["status"] == status
    assert body["requestId"] == headers["X-Request-Id"]
    assert re.fullmatch("[0-9a-f]{32}", body["requestId"])
    assert body["instance"] == "urn:request:" + body["requestId"]
    assert SENTINEL not in raw


def metric(service, name):
    status, body = management(service, "/actuator/metrics/" + name)
    assert status == 200, (service, name, status)
    for measurement in body["measurements"]:
        assert math.isfinite(measurement["value"]) and measurement["value"] >= 0
    for tag in body.get("availableTags", []):
        assert tag["tag"] not in {"requestId", "path", "url", "userId"}
        assert all(SENTINEL not in value for value in tag["values"])
    return body


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db-recovery", action="store_true")
    args = parser.parse_args()
    wait(lambda: http(8000, PING)[0] == 200)
    for service in SERVICES:
        health(service, {"": (200, "UP"), "/liveness": (200, "UP"), "/readiness": (200, "UP")})
        for endpoint in ["env", "configprops", "beans", "loggers", "heapdump", "threaddump"]:
            assert management(service, "/actuator/" + endpoint)[0] == 404
        for name in ["jvm.memory.used", "jvm.threads.live", "process.uptime", "system.cpu.count"]:
            metric(service, name)
    for path in ["/actuator/health", "/actuator/metrics", "/livez", "/readyz",
                 "/api/group-purchases/actuator/health", "/missing/" + SENTINEL]:
        problem(8000, path, 404)
    problem(8000, PING, 404, "POST")
    problem(8000, "/missing", 404, "HEAD")
    problem(8080, "/api/missing/" + SENTINEL, 404)
    problem(8080, PING, 405, "POST")
    before = metric("group-purchase-service", "http.server.requests?tag=method:GET&tag=status:200&tag=uri:" + PING)
    def ping(_):
        status, headers, raw = http(8000, PING + "?secret=" + SENTINEL,
                                     headers={"X-Request-Id": "a" * 32, "Authorization": SENTINEL, "Cookie": SENTINEL})
        assert status == 200 and json.loads(raw)["message"] == "pong"
        identifier = headers["X-Request-Id"]
        assert re.fullmatch("[0-9a-f]{32}", identifier) and identifier != "a" * 32
        return identifier
    sequential = [ping(i) for i in range(3)]
    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as executor:
        ids = sequential + list(executor.map(ping, range(12)))
    assert len(set(ids)) == len(ids)
    after = metric("group-purchase-service", "http.server.requests?tag=method:GET&tag=status:200&tag=uri:" + PING)
    count = lambda body: next(m["value"] for m in body["measurements"] if m["statistic"] == "COUNT")
    assert count(after) - count(before) == len(ids)
    for name in ["jdbc.connections.max", "hikaricp.connections.max", "hikaricp.connections.active"]:
        metric("group-purchase-service", name)
    for service in SERVICES:
        metric(service, "http.server.requests")
    if args.db_recovery:
        identity = compose("ps", "-q", "group-purchase-service").strip()
        process_before = process_identity(identity)
        try:
            compose("stop", "postgres")
            wait(lambda: health("group-purchase-service", {"/liveness": (200, "UP"),
                    "/readiness": (503, "DOWN"), "": (503, "DOWN")}), 60)
            assert http(8080, PING)[0] == 200
            metric("group-purchase-service", "http.server.requests?tag=status:503")
        finally:
            compose("start", "postgres")
            wait(lambda: health("group-purchase-service", {"": (200, "UP"),
                    "/readiness": (200, "UP"), "/liveness": (200, "UP")}), 180)
        assert compose("ps", "-q", "group-purchase-service").strip() == identity
        assert process_identity(identity) == process_before
        wait(lambda: http(8000, PING)[0] == 200)
    time.sleep(1)  # allow completion logs to flush; never a convergence success criterion
    for service in SERVICES:
        raw = compose("logs", "--no-log-prefix", "--no-color", service)
        assert SENTINEL not in raw, service
        lines = [json.loads(line) for line in raw.splitlines() if line.strip()]
        assert lines, service
        for event in lines:
            assert {"@timestamp", "level", "logger_name", "thread_name", "message", "service", "environment"} <= event.keys()
            assert event["environment"] == "docker", (service, event["environment"])
        if service in {"api-gateway", "group-purchase-service"}:
            events = [event for event in lines if event.get("event") == "http_request_completed"]
            for identifier in ids:
                matching = [event for event in events if event["requestId"] == identifier]
                assert len(matching) == 1, (service, identifier, len(matching))
                event = matching[0]
                assert event["status"] == 200 and event["termination"] == "completed"
                assert math.isfinite(event["durationMs"]) and event["durationMs"] >= 0
    print("PASS: 006 errors, IDs, JSON logs, privacy, probes, metrics and exposure" +
          (", same-container DB recovery" if args.db_recovery else ""))


if __name__ == "__main__":
    main()
