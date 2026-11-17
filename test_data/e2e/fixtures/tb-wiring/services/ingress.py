#!/usr/bin/env python3
"""Ingress service (stdlib only): exposes /health and /fetch?value=N, proxying to
the upstream it derives from UPSTREAM. Wiring lives in run.env - do not change it."""
import json
import os
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(os.environ.get("INGRESS_PORT", "8771"))
UPSTREAM = os.environ.get("INGRESS_UPSTREAM", "")


class Handler(BaseHTTPRequestHandler):
    def _send(self, status, body, ctype="text/plain"):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.end_headers()
        self.wfile.write(body.encode())

    def do_GET(self):
        path, _, query = self.path.partition("?")
        if path == "/health":
            self._send(200, f"ingress ok (upstream={UPSTREAM or 'unset'})")
            return
        if path == "/fetch":
            params = dict(pair.split("=", 1) for pair in query.split("&") if "=" in pair)
            if "value" not in params or not UPSTREAM:
                self._send(400, "bad request")
                return
            try:
                with urllib.request.urlopen(
                    f"{UPSTREAM}/transform?value={params['value']}", timeout=5
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
