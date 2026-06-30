#!/usr/bin/env python3
"""Stub payment gateway for local smoke testing of the booking slice.

Booking's `@Http` payment resource points at http://localhost:9100 (see resources.toml [http]).
This stub answers the three endpoints the booking saga calls:

  POST /authorize  -> {"approved": true,  "receiptId": "<uuid>"}
  POST /void       -> {"status": "voided"}
  POST /refund     -> {"receiptId": "<uuid>"}

Decline a payment to exercise the BER compensation path by sending a body containing
"decline" (e.g. a customer id with that substring), or by setting DECLINE=1 in the env.

Run:  python3 scripts/stub-gateway.py     (Ctrl-C to stop)
Port: STUB_PORT env var (default 9100).
"""
import json
import os
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("STUB_PORT", "9100"))
ALWAYS_DECLINE = os.environ.get("DECLINE", "") not in ("", "0", "false")


class Gateway(BaseHTTPRequestHandler):
    def _send(self, payload):
        body = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0") or "0")
        raw = self.rfile.read(length).decode("utf-8", "replace") if length else ""
        path = self.path.rstrip("/")
        if path.endswith("/authorize"):
            declined = ALWAYS_DECLINE or "decline" in raw.lower()
            self._send({"approved": not declined, "receiptId": str(uuid.uuid4())})
        elif path.endswith("/void"):
            self._send({"status": "voided"})
        elif path.endswith("/refund"):
            self._send({"receiptId": str(uuid.uuid4())})
        else:
            self._send({"error": "unknown endpoint", "path": self.path})

    def log_message(self, fmt, *args):  # quieter logs
        print("[stub-gateway] " + (fmt % args))


if __name__ == "__main__":
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Gateway)
    print(f"[stub-gateway] listening on http://127.0.0.1:{PORT} "
          f"(decline mode: {'ON' if ALWAYS_DECLINE else 'off'})")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[stub-gateway] stopping")
        server.shutdown()
