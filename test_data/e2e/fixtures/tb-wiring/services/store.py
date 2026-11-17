#!/usr/bin/env python3
"""Store service (stdlib only): /health and /store?value=N, which multiplies by
seven and answers. Wiring lives in run.env - do not change it."""
import os
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(os.environ.get("STORE_PORT", "8773"))


class Handler(BaseHTTPRequestHandler):
    def _send(self, status, body, ctype="text/plain"):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.end_headers()
        self.wfile.write(body.encode())

    def do_GET(self):
        path, _, query = self.path.partition("?")
        if path == "/health":
            self._send(200, f"store ok")
            return
        if path == "/store":
            params = dict(pair.split("=", 1) for pair in query.split("&") if "=" in pair)
            if "value" not in params:
                self._send(400, "bad request")
                return
            value = int(params["value"]) * 7
            self._send(200, f"stored: {value}")
            return
        self._send(404, "not found")

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
