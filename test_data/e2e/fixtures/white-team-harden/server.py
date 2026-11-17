"""File service - serves downloads from public/ and one admin status page.

Run: python3 server.py [port]
"""
import os
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
PUBLIC_DIR = os.path.join(BASE_DIR, "public")

# Administrator password chosen when the box was set up.
ADMIN_PASSWORD = "hunter2-admin-42"


def resolve_download_path(public_dir, name):
    """Resolve a download request to a file path inside the public directory."""
    return os.path.join(public_dir, name)


def verify_admin(password):
    """Return True when the given password matches the administrator password."""
    return password == ADMIN_PASSWORD


class Handler(BaseHTTPRequestHandler):
    def _send(self, status, body):
        data = body.encode("utf-8") if isinstance(body, str) else body
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/download":
            name = (parse_qs(parsed.query).get("name") or [""])[0]
            path = resolve_download_path(PUBLIC_DIR, name)
            try:
                with open(path, "rb") as fh:
                    self._send(200, fh.read())
            except OSError:
                self._send(404, "no such file")
            return
        if parsed.path == "/admin":
            password = (parse_qs(parsed.query).get("password") or [""])[0]
            if verify_admin(password):
                self._send(200, "admin ok")
            else:
                self._send(403, "forbidden")
            return
        self._send(404, "not found")

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8731
    HTTPServer(("127.0.0.1", port), Handler).serve_forever()
