"""Check the actual Eureka registration and its reachability inside Compose."""

import argparse
import json
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request


def registration(url):
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            instances = json.load(response)["application"].get("instance", [])
            return instances if isinstance(instances, list) else [instances]
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return []
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--timeout", type=float, default=180)
    parser.add_argument("--absent", action="store_true", help="Wait for normal deregistration")
    args = parser.parse_args()
    deadline = time.monotonic() + args.timeout
    last_error = "No registration observed"
    while time.monotonic() < deadline:
        try:
            instances = registration("http://localhost:8761/eureka/apps/GROUP-PURCHASE")
            if args.absent:
                if not instances:
                    print("PASS: GROUP-PURCHASE is absent from the Eureka response cache")
                    return
                raise ValueError(f"Still registered: {[item['instanceId'] for item in instances]}")
            if len(instances) != 1:
                raise ValueError(f"Expected one instance, found {len(instances)}")
            instance = instances[0]
            host, port = instance["hostName"], int(instance["port"]["$"])
            if instance["status"] != "UP" or host != "group-purchase-service" or port != 8080:
                raise ValueError(f"Unexpected registration: {instance}")
            health_url = f"http://{host}:{port}/actuator/health"
            if instance["healthCheckUrl"] != health_url:
                raise ValueError(f"Incorrect health URL: {instance['healthCheckUrl']}")
            result = subprocess.run(
                ["docker", "compose", "exec", "-T", "config-server", "curl", "--fail",
                 "--silent", "--show-error", "--max-time", "5", health_url],
                cwd=Path(__file__).resolve().parents[1], capture_output=True, text=True, timeout=15,
            )
            if result.returncode or json.loads(result.stdout).get("status") != "UP":
                raise ValueError(f"Registered address failed: {result.stderr or result.stdout}")
            print(f"PASS: {instance['instanceId']} UP; {health_url} reachable from config-server")
            return
        except (urllib.error.URLError, OSError, ValueError, KeyError, subprocess.TimeoutExpired) as error:
            last_error = str(error)
        time.sleep(min(2, max(0, deadline - time.monotonic())))
    raise SystemExit(f"Discovery check timed out after {args.timeout}s: {last_error}")


if __name__ == "__main__":
    main()
