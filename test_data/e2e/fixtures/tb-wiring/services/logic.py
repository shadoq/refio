#!/usr/bin/env python3
"""Logic service (stdlib only): /health and /transform?value=N, which adds one and
proxies to its own upstream (the store). Wiring lives in run.env - do not change it."""
import os
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(os.environ.get("LOGIC_PORT", "8772"))
UPSTREAM = os.environ.get("LOGIC_UPSTREAM", "")


class Handler(BaseHTTPRequestHandler):
    def _send(self, status, body, ctype="text/plain"):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.end_headers()
        self.wfile.write(body.encode())

    def do_GET(self):
        path, _, query = self.path.partition("?")
        if path == "/health":
            self._send(200, f"logic ok (upstream={UPSTREAM or 'unset'})")
            return
        if path == "/transform":
            params = dict(pair.split("=", 1) for pair in query.split("&") if "=" in pair)
            if "value" not in params or not UPSTREAM:
                self._send(400, "bad request")
                return
            value = int(params["value"]) + 1
            try:
                with urllib.request.urlopen(
                    f"{UPSTREAM}/store?value={value}", timeout=5
                ) as resp:
                    self._send(200, resp.read().decode())
            except Exception as exc:
                self._send(502, f"upstream error: {exc}")
            return
        self._send(404, "not found")

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
