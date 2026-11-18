"""Wait for the real Gateway route and check that private paths stay closed."""

import argparse
import json
import time
import urllib.error
import urllib.request


BASE = "http://127.0.0.1:8000"
PING = "/api/group-purchases/ping"
PRIVATE = (
    "/internal/auth/check",
    "/internal/probe",
    "/api/group-purchases/internal/probe",
    "/actuator/health",
    "/api/group-purchases/actuator/health",
    "/eureka/apps",
    "/config-server/application/default",
    "/group-purchase/api/group-purchases/ping",
    "/member/api/members/me",
    "/api/group-purchases/ping/",
    "/api/group-purchases/ping/extra",
)


def request(path, method="GET"):
    try:
        with urllib.request.urlopen(
            urllib.request.Request(BASE + path, method=method), timeout=8
        ) as response:
            return response.status, response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--timeout", type=float, default=180)
    parser.add_argument("--absent", action="store_true",
                        help="Wait for the Gateway's own discovery cache to have no target")
    args = parser.parse_args()
    deadline = time.monotonic() + args.timeout
    expected = 503 if args.absent else 200
    last_error = "No Gateway response"
    while time.monotonic() < deadline:
        try:
            status, body = request(PING)
            if status != expected:
                raise ValueError(f"ping returned {status}, expected {expected}")
            if not args.absent and json.loads(body) != {
                "service": "group-purchase", "message": "pong"
            }:
                raise ValueError(f"Unexpected ping body: {body}")
            for path in PRIVATE:
                private_status, _ = request(path)
                if private_status != 404:
                    raise ValueError(f"Private path {path} returned {private_status}")
            for method in ("POST", "PUT", "DELETE"):
                private_status, _ = request(PING, method)
                if private_status != 404:
                    raise ValueError(f"{method} ping returned {private_status}")
            auth_status, auth_body = request("/api/group-purchases/mine")
            if auth_status != 401 or json.loads(auth_body)["code"] != "UNAUTHENTICATED":
                raise ValueError(f"Anonymous seller list returned {auth_status}")
            print(f"PASS: Gateway ping {expected}; private paths and methods 404")
            return
        except (urllib.error.URLError, TimeoutError, OSError, ValueError) as error:
            last_error = str(error)
        time.sleep(min(2, max(0, deadline - time.monotonic())))
    raise SystemExit(f"Gateway check timed out after {args.timeout}s: {last_error}")


if __name__ == "__main__":
    main()
