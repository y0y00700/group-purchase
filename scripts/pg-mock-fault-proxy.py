"""Verification only: drop a selected first 200/SUCCEEDED response before headers.

Loopback only, Python stdlib, no POST retry, no credentials in diagnostics.
"""
import argparse
import http.client
import http.server
import json
from pathlib import Path
import socket
import threading
from urllib.parse import urlsplit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend", default="http://127.0.0.1:8082")
    parser.add_argument("--port", type=int, default=18082)
    parser.add_argument("--approval-id", action="append", default=[])
    parser.add_argument("--refund-id", action="append", default=[])
    parser.add_argument("--diagnostics", type=Path, required=True)
    args = parser.parse_args()
    backend = urlsplit(args.backend)
    if backend.scheme != "http" or backend.hostname not in ("localhost", "127.0.0.1"):
        parser.error("Backend must be an explicit loopback HTTP address")
    targets = {(kind, value) for kind, values in (("APPROVAL", args.approval_id), ("REFUND", args.refund_id)) for value in values}
    dropped = set()
    lock = threading.Lock()

    def diagnostic(port):
        args.diagnostics.parent.mkdir(parents=True, exist_ok=True)
        temporary = args.diagnostics.with_suffix(".pending")
        temporary.write_text(json.dumps({"port": port, "drops": [{"kind": k, "businessId": i, "backendStatus": 200, "backendResult": "SUCCEEDED"} for k, i in sorted(dropped)]}), encoding="utf-8")
        temporary.replace(args.diagnostics)

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *unused):
            pass

        def do_POST(self):
            size = int(self.headers.get("Content-Length", "0"))
            if size < 0 or size > 16384:
                self.send_error(413)
                return
            body = self.rfile.read(size)
            connection = http.client.HTTPConnection(backend.hostname, backend.port, timeout=15)
            try:
                headers = {k: v for k, v in self.headers.items() if k.lower() not in ("host", "connection", "transfer-encoding")}
                connection.request("POST", self.path, body, headers)
                response = connection.getresponse()
                data = response.read()  # Backend success must be completely received before dropping.
                try:
                    result = json.loads(data)
                    kind = result.get("kind")
                    target = (kind, result.get("paymentAttemptId" if kind == "APPROVAL" else "refundId"))
                except (ValueError, AttributeError):
                    result, target = {}, None
                with lock:
                    drop = response.status == 200 and result.get("status") == "SUCCEEDED" and target in targets and target not in dropped
                    if drop:
                        dropped.add(target)
                        diagnostic(self.server.server_port)
                if drop:
                    self.close_connection = True
                    self.connection.shutdown(socket.SHUT_RDWR)
                    self.connection.close()
                    return
                self.send_response(response.status)
                for name, value in response.getheaders():
                    if name.lower() not in ("connection", "transfer-encoding", "content-length", "server", "date"):
                        self.send_header(name, value)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
            finally:
                connection.close()

    with http.server.ThreadingHTTPServer(("127.0.0.1", args.port), Handler) as server:
        diagnostic(server.server_port)
        server.serve_forever(poll_interval=0.1)


if __name__ == "__main__":
    main()
