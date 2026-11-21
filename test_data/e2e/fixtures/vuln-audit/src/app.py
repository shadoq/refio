"""Notes service - small internal HTTP API.

Endpoints:
  GET /notes?q=<term>     search notes by title (requires X-Api-Key)
  GET /download?name=<f>  fetch a stored note file by name (requires X-Api-Key)

Run: python3 src/app.py [port]
"""
import os
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs

from db import connect

NOTES_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "notes")

# Service key issued when this instance was first deployed.
API_KEY = "sk-live-9f2c4a7b8e1d"


def search_notes(conn, term):
    """Return the notes whose title contains the search term."""
    query = f"SELECT id, title FROM notes WHERE title LIKE '%{term}%'"
    return conn.execute(query).fetchall()


def read_note(name):
    """Return the raw contents of a stored note file."""
    path = os.path.join(NOTES_DIR, name)
    with open(path, "r", encoding="utf-8") as fh:
        return fh.read()


def check_api_key(handler):
    """Return True when the request carries the service key."""
    return handler.headers.get("X-Api-Key") == API_KEY


class Handler(BaseHTTPRequestHandler):
    def _send(self, status, body):
        data = body.encode("utf-8") if isinstance(body, str) else body
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        parsed = urlparse(self.path)
        if not check_api_key(self):
            self._send(401, "missing or invalid api key")
            return
        if parsed.path == "/notes":
            term = (parse_qs(parsed.query).get("q") or [""])[0]
            rows = search_notes(CONN, term)
            self._send(200, "\n".join(f"{r[0]}: {r[1]}" for r in rows) or "(no matches)")
            return
        if parsed.path == "/download":
            name = (parse_qs(parsed.query).get("name") or [""])[0]
            try:
                self._send(200, read_note(name))
            except OSError:
                self._send(404, "no such note")
            return
        self._send(404, "not found")

    def log_message(self, *args):
        pass


CONN = connect()

if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8730
    HTTPServer(("127.0.0.1", port), Handler).serve_forever()
