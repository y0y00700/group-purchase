"""Bounded real HTTP PG contract smoke including approval/refund response loss.

Requires explicit control override. Creates fresh UUID businesses and preserves them.
The proxy child is always stopped; no DB/topic/volume is deleted.
"""
import argparse
import concurrent.futures
import http.client
import json
from pathlib import Path
import subprocess
import sys
import time
import uuid
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]


def wait(check, timeout=30, label="condition"):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            value = check()
            if value:
                return value
        except (OSError, ValueError, http.client.HTTPException) as error:
            last = type(error).__name__
        time.sleep(0.1)
    raise AssertionError(f"Deadline exceeded: {label}; last={last}")


class PgClient:
    def __init__(self, base):
        self.base = base
        self.api = (ROOT / "secrets/pg_mock_api_credential").read_text().strip()
        self.control = (ROOT / "secrets/pg_mock_control_credential").read_text().strip()

    def request(self, method, path, body=None, control=False, base=None, timeout=12, credential=True, raw=None, headers=None):
        url = urlsplit(base or self.base)
        connection = http.client.HTTPConnection(url.hostname, url.port, timeout=timeout)
        supplied = {"Content-Type": "application/json", "X-Request-Id": uuid.uuid4().hex}
        if credential:
            supplied["Authorization"] = "Bearer " + (self.control if control else self.api)
        supplied.update(headers or {})
        data = raw if raw is not None else json.dumps(body).encode() if body is not None else None
        try:
            connection.request(method, path, data, supplied)
            response = connection.getresponse()  # http.client does not retry POST.
            payload = response.read()
            return response.status, dict(response.getheaders()), json.loads(payload) if payload else None
        finally:
            connection.close()

    def post(self, payment, refund=None, amount=100, base=None, timeout=12, key=None):
        body = {"paymentAttemptId": payment, "amount": amount, "currency": "KRW"}
        if refund:
            body["refundId"] = refund
        return self.request("POST", "/pg/v1/" + ("refunds" if refund else "approvals"), body, base=base, timeout=timeout,
                            headers={"Idempotency-Key": key or ("refund:" + refund if refund else "approve:" + payment)})

    def get(self, payment, refund=None):
        return self.request("GET", "/pg/v1/" + ("refunds/" + refund if refund else "approvals/" + payment))

    def scenario(self, business, kind="APPROVAL", processing=0, response=0, decision="APPROVE", failures=0):
        return self.request("PUT", f"/__admin/v1/scenarios/{kind}/{business}",
                            {"processingDelayMs": processing, "firstResponseDelayMs": response, "approvalDecision": decision, "transientFailures": failures}, control=True)

    def reconcile(self, payment):
        status, _, result = self.request("GET", "/__admin/v1/transactions?paymentAttemptId=" + payment, control=True)
        assert status == 200, result
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="http://127.0.0.1:8082")
    parser.add_argument("--output", type=Path, default=ROOT / ".tmp/014-baseline/smoke.json")
    args = parser.parse_args()
    pg = PgClient(args.base)
    records = []

    def passed(name, **evidence):
        records.append({"check": name, **evidence})
        print("PASS", name, flush=True)

    wait(lambda: pg.request("GET", "/actuator/health/readiness", credential=False)[0] == 200, label="PG readiness")
    assert pg.request("GET", "/actuator/info", credential=False)[0] == 401
    assert pg.request("GET", "/__admin/v1/transactions?paymentAttemptId=" + str(uuid.uuid4()))[0] == 403
    passed("authentication and protected diagnostics")
    payment, refund = str(uuid.uuid4()), str(uuid.uuid4())
    first = pg.post(payment)
    assert first[0] == 200 and first[2]["status"] == "SUCCEEDED", first
    assert pg.post(payment)[2] == first[2]
    assert pg.post(payment, amount=101)[2]["code"] == "IDEMPOTENCY_CONFLICT"
    assert pg.post(payment, key="different:" + payment)[2]["code"] == "BUSINESS_KEY_CONFLICT"
    assert pg.post(payment, refund, amount=99)[2]["code"] == "REFUND_AMOUNT_MISMATCH"
    assert pg.scenario(refund, "REFUND", failures=1)[0] == 200
    assert pg.post(payment, refund)[2]["code"] == "PG_TEMPORARILY_UNAVAILABLE"
    failed = pg.get(payment, refund)[2]
    assert failed["status"] == "RETRYABLE_FAILURE" and failed["refundedAt"] is None
    final_refund = pg.post(payment, refund)
    assert final_refund[0] == 200 and pg.post(payment, refund)[2] == final_refund[2]
    assert pg.post(payment, str(uuid.uuid4()))[2]["code"] == "FULL_REFUND_ALREADY_REGISTERED"
    reconciliation = pg.reconcile(payment)
    assert len(reconciliation["ledger"]) == 2 and len(reconciliation["attempts"]) == 3
    assert pg.get(payment)[2] == first[2]
    passed("approval duplicate conflicts full refund transient retry reconciliation", paymentAttemptId=payment)
    declined = str(uuid.uuid4())
    assert pg.scenario(declined, decision="DECLINE")[0] == 200
    decline = pg.post(declined)
    assert decline[0] == 200 and decline[2]["status"] == "DECLINED"
    assert pg.post(declined)[2] == decline[2] and len(pg.reconcile(declined)["ledger"]) == 0
    assert pg.scenario(declined)[2]["code"] == "SCENARIO_ALREADY_BOUND"
    passed("definitive decline and immutable binding", paymentAttemptId=declined)
    pending = str(uuid.uuid4())
    pg.scenario(pending, processing=1200)
    status, headers, body = pg.post(pending)
    assert status == 202 and headers["Location"].endswith(pending) and headers["Retry-After"] == "1"
    wait(lambda: pg.get(pending)[2]["status"] == "SUCCEEDED", label="delayed approval")
    assert len(pg.reconcile(pending)["ledger"]) == 1
    passed("processing delay and scheduler recovery", paymentAttemptId=pending)
    for processing, response in ((1000, 0), (0, 1200)):
        business = str(uuid.uuid4())
        pg.scenario(business, processing=processing, response=response)
        try:
            pg.post(business, timeout=0.15)
            # A processing delay alone returns a valid 202 immediately; add first-response delay for timeout-before-effect below.
            assert processing > 0
        except TimeoutError:
            assert response > 0
        wait(lambda: pg.get(business)[2]["status"] == "SUCCEEDED", label="post timeout lookup")
        snapshot = pg.get(business)[2]
        assert pg.post(business)[2] == snapshot and len(pg.reconcile(business)["ledger"]) == 1
    timeout_before = str(uuid.uuid4())
    pg.scenario(timeout_before, processing=1500, response=1800)
    try:
        pg.post(timeout_before, timeout=0.15)
        raise AssertionError("Expected client timeout")
    except TimeoutError:
        assert pg.get(timeout_before)[2]["status"] == "PENDING"
    wait(lambda: pg.get(timeout_before)[2]["status"] == "SUCCEEDED", label="timeout-before-effect recovery")
    passed("timeouts before and after effect never imply failure", paymentAttemptId=timeout_before)
    lost_payment, lost_refund = str(uuid.uuid4()), str(uuid.uuid4())
    diagnostics = args.output.with_name("proxy.json")
    diagnostics.parent.mkdir(parents=True, exist_ok=True)
    if diagnostics.exists():
        diagnostics = diagnostics.with_name("proxy-" + uuid.uuid4().hex + ".json")
    proxy = subprocess.Popen([sys.executable, str(ROOT / "scripts/pg-mock-fault-proxy.py"), "--backend", args.base, "--port", "0",
                              "--approval-id", lost_payment, "--refund-id", lost_refund, "--diagnostics", str(diagnostics)], cwd=ROOT)
    try:
        wait(diagnostics.exists, label="proxy ready")
        port = json.loads(diagnostics.read_text())["port"]
        for ref in (None, lost_refund):
            try:
                pg.post(lost_payment, ref, base=f"http://127.0.0.1:{port}")
                raise AssertionError("Proxy must produce EOF/reset, not an HTTP error")
            except (http.client.RemoteDisconnected, ConnectionResetError):
                pass
            snapshot = pg.get(lost_payment, ref)[2]
            assert snapshot["status"] == "SUCCEEDED"
            assert pg.post(lost_payment, ref)[2] == snapshot
        evidence = json.loads(diagnostics.read_text())
        assert len(evidence["drops"]) == 2 and len(pg.reconcile(lost_payment)["ledger"]) == 2
        passed("actual approval and refund response loss after backend success", paymentAttemptId=lost_payment, proxy=evidence)
    finally:
        proxy.terminate()
        try:
            proxy.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proxy.kill(); proxy.wait(timeout=5)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"base": args.base, "checks": records, "stablePayment": payment, "stableSnapshot": pg.reconcile(payment)}, indent=2), encoding="utf-8")
    print(f"PASS {len(records)} PG HTTP smoke groups; evidence={args.output}", flush=True)


if __name__ == "__main__":
    main()
