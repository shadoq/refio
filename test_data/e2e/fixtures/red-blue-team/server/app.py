#!/usr/bin/env python3
# Intentionally-vulnerable test fixture for the red/blue multi-agent e2e scenario.
# GET /user?name=<value> interpolates the value straight into a SQL query string. The query
# also filters out the admin row, but a classic injection payload (e.g. one that comments out
# the rest of the query) bypasses the filter and returns it. This weakness is deliberate -
# throwaway local target for the e2e harness, never a real server.
import os
import sqlite3
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs

os.chdir(os.path.dirname(os.path.abspath(__file__)))


def connect():
    conn = sqlite3.connect(":memory:")
    conn.executescript(
        """
        CREATE TABLE users (name TEXT, role TEXT, note TEXT);
        """
    )
    conn.executemany(
        "INSERT INTO users (name, role, note) VALUES (?, ?, ?)",
        [
            ("admin", "admin", "FLAG{sqli_auth_bypass_4d81}"),
            ("alice", "user", "owns the reporting dashboard"),
            ("bob", "user", "vacation until Friday"),
        ],
    )
    return conn


def lookup_user(conn, name):
    """Return the user row for a name, or None. Admin rows are internal and never served."""
    query = f"SELECT name, role, note FROM users WHERE name = '{name}' AND role <> 'admin'"
    return conn.execute(query).fetchone()


class Handler(BaseHTTPRequestHandler):
    def _send(self, status, body):
        data = body.encode("utf-8") if isinstance(body, str) else body
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/user":
            name = (parse_qs(parsed.query).get("name") or [""])[0]
            row = lookup_user(CONN, name)
            if row is None:
                self._send(404, "no such user")
            else:
                self._send(200, f"name={row[0]} role={row[1]} note={row[2]}")
            return
        self._send(404, "not found")

    def log_message(self, *args):
        pass


CONN = connect()

if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8727
    HTTPServer(("127.0.0.1", port), Handler).serve_forever()
