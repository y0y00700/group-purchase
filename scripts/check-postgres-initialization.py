"""Exercise init failures in disposable Compose projects, never the normal volume."""

import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
NAMES = ("postgres", "member", "group_purchase", "payment", "notification")


def scenario(kind):
    project = "gp-db-check-" + secrets.token_hex(6)
    (ROOT / ".tmp").mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=project, dir=ROOT / ".tmp") as tmp:
        directory = Path(tmp)
        secret_defs = {}
        for name in NAMES:
            path = directory / f"{name}_password"
            # Include SQL/shell metacharacters to verify literal quoting.
            path.write_text("test-'\\\"$;" + secrets.token_hex(24), encoding="ascii")
            secret_defs[f"{name}_password"] = {"file": path.as_posix()}
        if kind == "missing":
            (directory / "member_password").unlink()
        if kind == "empty":
            (directory / "member_password").write_text("")
        init = directory / "init"
        shutil.copytree(ROOT / "infrastructure/postgres/init", init)
        if kind == "partial":
            (init / "005-fail.sh").write_text(
                "#!/bin/sh\npsql -X -v ON_ERROR_STOP=1 -U postgres -d postgres <<'SQL'\n"
                "CREATE ROLE partial_init_marker;\nSELECT 1/0;\nSQL\nexit 1\n",
                encoding="ascii", newline="\n")
        override = directory / "override.json"
        override.write_text(json.dumps({"secrets": secret_defs, "services": {"postgres": {
            "ports": ["127.0.0.1:0:5432"],
            "volumes": [f"{init.as_posix()}:/docker-entrypoint-initdb.d:ro"]}}}), encoding="utf-8")
        env = os.environ.copy()
        env.update(COMPOSE_PROJECT_NAME=project, POSTGRES_PORT="0",
                   COMPOSE_FILE=os.pathsep.join((str(ROOT / "compose.yml"), str(override))))

        def compose(*args, check=True):
            result = subprocess.run(["docker", "compose", *args], cwd=ROOT, env=env,
                                    capture_output=True, text=True, timeout=150)
            if check and result.returncode:
                # Never print server logs that may contain sensitive statements.
                raise RuntimeError(f"{kind}: compose {args[0]} failed ({result.returncode})")
            return result

        try:
            if kind == "missing":
                result = compose("up", "-d", "postgres", check=False)
                if result.returncode != 0:
                    print("PASS missing secret: Compose rejected startup", flush=True)
                    return
            else:
                compose("up", "-d", "postgres")
            if kind in ("missing", "empty", "partial"):
                deadline = time.monotonic() + 45
                while time.monotonic() < deadline:
                    state = json.loads(compose("ps", "-a", "--format", "json", "postgres").stdout)
                    if state["State"] == "exited":
                        break
                    time.sleep(1)
                else:
                    raise RuntimeError(f"{kind}: init did not exit")
                logs = compose("logs", "postgres").stdout
                expected = "division by zero" if kind == "partial" else "Missing/empty secret: member"
                assert expected in logs, "Expected init failure not found"
                if kind == "partial":
                    compose("up", "-d", "--wait", "--wait-timeout", "60", "postgres")
                    logs = compose("logs", "postgres").stdout
                    assert "Skipping initialization" in logs
                    query = ("SELECT EXISTS (SELECT FROM pg_roles WHERE rolname='partial_init_marker') "
                             "AND NOT EXISTS (SELECT FROM pg_database WHERE datname='member')")
                    result = compose("exec", "-T", "postgres", "psql", "-X", "-U", "postgres",
                                     "-d", "postgres", "-Atc", query)
                    assert result.stdout.strip() == "t"
                    print("PASS partial init: stopped; restart skips init, missing DB remains", flush=True)
                else:
                    print(f"PASS {kind} secret: init stopped before service role creation", flush=True)
            else:
                compose("up", "-d", "--wait", "--wait-timeout", "60", "postgres")
                subprocess.run([sys.executable, str(ROOT / "scripts/check-postgres.py")],
                               cwd=ROOT, env=env, check=True, timeout=150)
                print("PASS quoted secrets: TCP authentication and boundaries", flush=True)
        finally:
            # project is generated above; only this scenario's resources are removed.
            compose("down", "-v", "--remove-orphans")


if __name__ == "__main__":
    for kind in ("missing", "empty", "partial", "quoted"):
        scenario(kind)
