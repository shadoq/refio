#!/usr/bin/env python3
"""Orders service (stdlib only). Reads APP_PORT and TOKEN_FILE from the environment."""
import os
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

TOKEN_FILE = os.environ.get("TOKEN_FILE")
PORT = int(os.environ.get("APP_PORT", "8080"))

if not TOKEN_FILE or not os.path.isfile(TOKEN_FILE):
    print(f"startup failed: TOKEN_FILE {TOKEN_FILE!r} is missing", file=sys.stderr)
    sys.exit(1)

with open(TOKEN_FILE, encoding="utf-8") as fh:
    TOKEN = fh.read().strip()


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/health":
            body = "ok"
        elif self.path == "/who":
            body = f"service token prefix: {TOKEN[:4]}"
        else:
            self.send_response(404)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.end_headers()
        self.wfile.write(body.encode())

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
