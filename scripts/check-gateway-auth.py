"""Exercise 008 against real Compose services and optionally isolated product JARs."""
import argparse
import base64
from contextlib import contextmanager, ExitStack
from http.client import HTTPConnection
from http.cookiejar import CookieJar
import importlib.util
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("member_checks", ROOT / "scripts/check-member-auth.py")
member_checks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(member_checks)
PROBE = "/api/group-purchases/auth-probe"


def read_secret(name):
    return (ROOT / "secrets" / name).read_text().rstrip("\r\n")


def request(port, path, method="GET", headers=None, body=None, opener=None):
    headers = dict(headers or {})
    if body is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", method=method,
                                 data=None if body is None else json.dumps(body).encode(), headers=headers)
    try:
        response = (opener or urllib.request.build_opener()).open(req, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        payload = response.read()
        return response.status, response.headers, json.loads(payload) if payload else None


def problem(result, status, code):
    actual, headers, body = result
    assert actual == status, (actual, status, None if body is None else body.get("code"))
    assert headers["Cache-Control"] == "no-store" and not headers.get_all("Set-Cookie")
    assert headers.get_content_type() == "application/problem+json"
    assert body["code"] == code and body["status"] == status
    assert body["requestId"] == headers["X-Request-Id"]
    return headers["X-Request-Id"]


def repeated_headers(port, method, headers):
    connection = HTTPConnection("127.0.0.1", port, timeout=10)
    try:
        connection.putrequest(method, PROBE)
        for key, value in headers:
            connection.putheader(key, value)
        connection.endheaders()
        with connection.getresponse() as response:
            return response.status, response.headers, json.loads(response.read())
    finally:
        connection.close()


def compose(*arguments, capture=False):
    return subprocess.run(["docker", "compose", *arguments], cwd=ROOT, check=True,
                          capture_output=capture, timeout=180).stdout


def gateway_healthy():
    health = compose("exec", "-T", "api-gateway", "curl", "--fail", "--silent", "--max-time", "5",
                     "http://localhost:9000/actuator/health", capture=True)
    assert json.loads(health)["status"] == "UP"


def wait_for(check, expected, seconds=180):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            value = check()
            if value[0] in expected:
                return value
        except OSError:
            pass
        time.sleep(1)
    raise AssertionError(f"Timed out waiting for {expected}")


@contextmanager
def fixture():
    tool = member_checks.tool
    login = "probe008_" + secrets.token_hex(6)
    password = secrets.token_hex(24)
    cookie_ids = set()
    member_id = None
    try:
        tool.prepare(login, password)
        member_id = member_checks.sql(f"SELECT member_id FROM app.members WHERE login_id='{login}';")
        yield login, password, member_id, cookie_ids
    finally:
        if member_id:
            ids = ",".join(f"'{value}'" for value in cookie_ids)
            member_checks.sql(f"DELETE FROM app.spring_session WHERE principal_name='{member_id}'"
                              + (f" OR session_id IN ({ids})" if ids else "") + ";"
                              + f"DELETE FROM app.members WHERE member_id='{member_id}';")


def cookie_value(jar, ids):
    value = next(item.value for item in jar if item.name == "GP_SESSION")
    ids.add(base64.b64decode(value, validate=True).decode())
    return value


def check_direct(group_port, expected):
    common = {"X-Internal-Caller": "api-gateway",
              "X-Authenticated-Member-Id": expected["memberId"], "X-Authenticated-Roles": "BUYER"}
    for credential in (None, "incorrect", read_secret("member_internal_credential")):
        headers = dict(common)
        if credential:
            headers["X-Internal-Credential"] = credential
        problem(request(group_port, PROBE, headers=headers), 403, "INTERNAL_CALLER_DENIED")
    common["X-Internal-Credential"] = read_secret("group_purchase_internal_credential")
    assert request(group_port, PROBE, headers=common)[2] == expected
    for key, status, code in (("X-Internal-Caller", 403, "INTERNAL_CALLER_DENIED"),
                              ("X-Internal-Credential", 403, "INTERNAL_CALLER_DENIED"),
                              ("X-Authenticated-Member-Id", 400, "INVALID_REQUEST"),
                              ("X-Authenticated-Roles", 400, "INVALID_REQUEST")):
        duplicate = [*common.items(), (key.lower(), common[key])]
        problem(repeated_headers(group_port, "GET", duplicate), status, code)
    for key, value in (("X-Authenticated-Member-Id", "1-1-1-1-1"),
                       ("X-Authenticated-Roles", "BUYER,BUYER"),
                       ("X-Authenticated-Roles", "ADMIN")):
        problem(request(group_port, PROBE, headers=common | {key: value}), 400, "INVALID_REQUEST")


def exercise(gateway_port, group_port, account, outage=False):
    login, password, member_id, ids = account
    jar = CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))

    def gateway(path=PROBE, method="GET", headers=None, body=None):
        return request(gateway_port, path, method, headers, body, opener)

    assert wait_for(lambda: gateway("/api/group-purchases/ping"), {200})[2]["message"] == "pong"
    assert wait_for(lambda: gateway("/api/members/me"), {401})[2]["code"] == "UNAUTHENTICATED"
    assert gateway("/internal/auth/check", "POST")[0] == 404
    assert gateway("/actuator/health")[0] == 404
    denied_ids = [problem(gateway(), 401, "UNAUTHENTICATED")]
    status, headers, seed = gateway("/api/auth/csrf")
    assert status == 200 and headers["Cache-Control"] == "no-store"
    cookie = headers.get_all("Set-Cookie")[0]
    assert all(value in cookie for value in ("GP_SESSION=", "Path=/", "HttpOnly", "SameSite=Lax"))
    assert "Domain=" not in cookie and "Secure" not in cookie
    old = cookie_value(jar, ids)
    expected = {"memberId": member_id, "roles": ["BUYER"]}
    status, headers, authenticated = gateway("/api/auth/login", "POST", {"X-CSRF-TOKEN": seed["token"]},
                                             {"loginId": login, "password": password})
    assert status == 200 and authenticated == expected and headers.get_all("Set-Cookie")
    rotated = cookie_value(jar, ids)
    assert rotated != old
    stale = request(gateway_port, PROBE, headers={"Cookie": f"GP_SESSION={old}"})
    denied_ids.append(problem(stale, 401, "UNAUTHENTICATED"))
    assert gateway("/api/members/me")[2] == expected
    assert gateway("/api/members/me", "HEAD")[2] is None
    status, headers, identity = gateway(headers={
        "X-Authenticated-Member-Id": "126260b2-de13-43b6-8503-a76ad6974d5a",
        "X-AUTHENTICATED-ROLES": "OPERATOR", "X-Internal-Caller": "api-gateway",
        "X-Internal-Credential": "forged",
    })
    assert status == 200 and identity == expected and headers["Cache-Control"] == "no-store"
    successful_id = headers["X-Request-Id"]
    status, _, body = gateway(method="HEAD")
    assert status == 200 and body is None
    _, _, csrf = gateway("/api/auth/csrf")
    token = csrf["token"]
    for token_header in ({}, {"X-CSRF-TOKEN": "wrong"}, {"X-HTTP-Method-Override": "GET"}):
        denied_ids.append(problem(gateway(PROBE + "?_csrf=" + token, "POST", token_header,
                                          {"_csrf": token}), 403, "CSRF_INVALID"))
    denied_ids.append(problem(repeated_headers(gateway_port, "POST", (
        ("Cookie", f"GP_SESSION={rotated}"), ("X-CSRF-TOKEN", token), ("x-csrf-token", token),
        ("Content-Length", "0"),
    )), 403, "CSRF_INVALID"))
    assert gateway(method="POST", headers={"X-CSRF-TOKEN": token}, body={"ignored": True})[2] == expected
    check_direct(group_port, expected)

    member_checks.sql(f"UPDATE app.members SET status='DISABLED' WHERE member_id='{member_id}';")
    denied_ids.append(problem(gateway(), 401, "UNAUTHENTICATED"))
    member_checks.sql(f"UPDATE app.members SET status='ACTIVE' WHERE member_id='{member_id}';")
    member_checks.sql(f"INSERT INTO app.member_roles(member_id, role) VALUES ('{member_id}', 'SELLER');")
    changed = expected | {"roles": ["BUYER", "SELLER"]}
    assert gateway()[2] == changed
    if outage:
        try:
            compose("stop", "member-service")
            codes = {502: "BAD_GATEWAY", 503: "AUTHENTICATION_UNAVAILABLE", 504: "GATEWAY_TIMEOUT"}
            unavailable = wait_for(gateway, codes)
            denied_ids.append(problem(unavailable, unavailable[0], codes[unavailable[0]]))
            assert gateway("/api/group-purchases/ping")[0] == 200
            gateway_healthy()
        finally:
            compose("up", "-d", "--wait", "--no-deps", "member-service")
        assert wait_for(gateway, {200})[2] == changed
        print(f"PASS: member unavailable ({unavailable[0]}), public ping, same Gateway recovers")
        try:
            compose("stop", "postgres")
            unavailable = gateway()
            code = {503: "AUTHENTICATION_UNAVAILABLE", 504: "GATEWAY_TIMEOUT"}
            assert unavailable[0] in code
            denied_ids.append(problem(unavailable, unavailable[0], code[unavailable[0]]))
            direct = request(8081, "/internal/auth/check", "POST", {
                "X-Internal-Caller": "api-gateway",
                "X-Internal-Credential": read_secret("member_internal_credential"),
                "Cookie": f"GP_SESSION={rotated}",
            }, {"method": "GET"})
            problem(direct, 503, "AUTHENTICATION_UNAVAILABLE")
            gateway_healthy()
        finally:
            compose("up", "-d", "--wait", "--no-deps", "postgres")
        assert wait_for(gateway, {200})[2] == changed
        print(f"PASS: member DB unavailable (Gateway {unavailable[0]}, member 503), same Gateway recovers")

    status, headers, _ = gateway("/api/auth/logout", "POST", {"X-CSRF-TOKEN": token})
    assert status == 204 and "Max-Age=0" in headers["Set-Cookie"] and not list(jar)
    denied_ids.append(problem(request(gateway_port, PROBE, headers={"Cookie": f"GP_SESSION={rotated}"}),
                              401, "UNAUTHENTICATED"))
    _, _, seed = gateway("/api/auth/csrf")
    cookie_value(jar, ids)
    assert gateway("/api/auth/login", "POST", {"X-CSRF-TOKEN": seed["token"]},
                   {"loginId": login, "password": password})[0] == 200
    expired = base64.b64decode(cookie_value(jar, ids), validate=True).decode()
    member_checks.sql(f"UPDATE app.spring_session SET last_access_time=0, expiry_time=0 WHERE session_id='{expired}';")
    denied_ids.append(problem(gateway(), 401, "UNAUTHENTICATED"))
    if group_port == 8080:
        logs = compose("logs", "--no-color", "--tail=300", "group-purchase-service", capture=True).decode()
        assert successful_id in logs
        assert all(request_id not in logs for request_id in denied_ids)
        logs += compose("logs", "--no-color", "--tail=300", "api-gateway", "member-service", capture=True).decode()
        assert all(value not in logs for value in (
            login, password, token, rotated, read_secret("member_internal_credential"),
            read_secret("group_purchase_internal_credential"),
        ))
    print(f"PASS: Gateway {gateway_port} cookie jar, CSRF, live roles, caller validation and fail-closed forwarding")


def stop(process):
    process.terminate()
    try:
        process.wait(timeout=20)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=10)


def exercise_jars():
    tool = member_checks.tool
    with tempfile.TemporaryDirectory(prefix="008-jars-") as directory, ExitStack() as processes:
        environment = os.environ | {"SPRING_PROFILES_ACTIVE": "local", "CONFIG_SERVER_URL": "http://127.0.0.1:8888"}

        def start(name, args, env):
            stream = processes.enter_context(open(Path(directory) / (name + ".log"), "wb"))
            child = subprocess.Popen(args, cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT)
            processes.callback(stop, child)

        start("member", tool.command(18081), tool.environment())
        group = ROOT / "services/group-purchase-service/build/libs/group-purchase-service-0.0.1-SNAPSHOT.jar"
        start("group", [tool.java(), "-jar", str(group), "--server.port=18080", "--eureka.client.enabled=false"],
              environment | {"SPRING_DATASOURCE_PASSWORD": read_secret("group_purchase_password"),
                             "APP_AUTH_INTERNAL_CREDENTIAL": read_secret("group_purchase_internal_credential")})
        gateway = ROOT / "infrastructure/api-gateway/build/libs/api-gateway-0.0.1-SNAPSHOT.jar"
        start("gateway", [tool.java(), "-jar", str(gateway), "--server.port=18000", "--management.server.port=19000",
                          "--eureka.client.enabled=false",
                          "--spring.cloud.discovery.client.simple.instances.member[0].uri=http://127.0.0.1:18081",
                          "--spring.cloud.discovery.client.simple.instances.group-purchase[0].uri=http://127.0.0.1:18080"],
              environment | {"APP_AUTH_MEMBER_INTERNAL_CREDENTIAL": read_secret("member_internal_credential"),
                             "APP_AUTH_GROUP_PURCHASE_INTERNAL_CREDENTIAL": read_secret("group_purchase_internal_credential")})
        with fixture() as account:
            exercise(18000, 18080, account)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dependency-outages", action="store_true",
                        help="Stop and restore the Compose member service and PostgreSQL in sequence")
    parser.add_argument("--jars", action="store_true", help="Also check three local product JARs on isolated ports")
    args = parser.parse_args()
    with fixture() as account:
        exercise(8000, 8080, account, args.dependency_outages)
    if args.jars:
        exercise_jars()


if __name__ == "__main__":
    main()
