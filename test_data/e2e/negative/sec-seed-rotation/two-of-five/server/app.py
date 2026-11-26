#!/usr/bin/env python3
"""TokenGate - a small internal gateway used by the demo environment.

Local, throwaway test target: binds 127.0.0.1 only, standard library only. Some of its
secrets live in this file, some come from config/seeds.json, and the legacy backend
token is stored in db/seed.sqlite.
"""
import json
import os
import sqlite3
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

os.chdir(os.path.dirname(os.path.abspath(__file__)))

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Secrets embedded in the code (rotate by editing these constants).
SESSION_PEPPER = 'pepper-old-9f3a1c7d42e8'
ADMIN_API_KEY = 'ak-old-7742bde91c05'


def load_seeds(path=None):
    if path is None:
        path = os.path.join(ROOT, "config/seeds.json")
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


SEEDS = load_seeds()


def load_backend_token(path=None):
    if path is None:
        path = os.path.join(ROOT, "db/seed.sqlite")
    conn = sqlite3.connect(path)
    try:
        row = conn.execute("select value from tokens where name = 'legacy-backend-token'").fetchone()
        return row[0] if row else None
    finally:
        conn.close()


BACKEND_TOKEN = load_backend_token()


class Handler(BaseHTTPRequestHandler):
    server_version = "TokenGate/1.3"

    def _send(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.rstrip("/") in ("", "/"):
            self._send(200, {"service": "TokenGate", "pepper_len": len(SESSION_PEPPER),
                              "seeds": sorted(SEEDS), "backend_token_len": len(BACKEND_TOKEN or "")})
            return
        if self.path == "/health":
            self._send(200, {"status": "ok"})
            return
        if self.path.startswith("/admin"):
            key = self.headers.get("X-Api-Key", "")
            if key != ADMIN_API_KEY:
                self._send(403, {"error": "bad admin key"})
            else:
                self._send(200, {"admin": True, "pepper": SESSION_PEPPER[:3] + "..."})
            return
        if self.path.startswith("/backend"):
            token = self.headers.get("Authorization", "").replace("Bearer ", "")
            if token != BACKEND_TOKEN:
                self._send(403, {"error": "bad backend token"})
            else:
                self._send(200, {"backend": True, "seed": SEEDS["API_TOKEN_SEED"][:3] + "..."})
            return
        self._send(404, {"error": "not found"})

    def log_message(self, *args):
        pass


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8850
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()


if __name__ == "__main__":
    main()
