#!/usr/bin/env python3
"""Widget Hub docs site - stdlib-only static server for the local e2e exercise.

Endpoints:
    GET /                - landing page with links
    GET /releases.html   - release table
    GET /status.html     - CI status table
    GET /team.html       - team roster
    GET /health          - readiness probe

Throwaway local target for the e2e harness - must NEVER be a real site.
"""
import os
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

WWW = os.path.join(os.path.dirname(os.path.abspath(__file__)), "www")

PAGES = ("/releases.html", "/status.html", "/team.html")


class Handler(BaseHTTPRequestHandler):
    def _send(self, status, body, ctype="text/html; charset=utf-8"):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.end_headers()
        self.wfile.write(body.encode("utf-8"))

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == "/health":
            self._send(200, "ok", "text/plain")
            return
        if path == "/":
            self._send(
                200,
                "<html><body><h1>Widget Hub</h1><ul>"
                + "".join(f"<li><a href='{p}'>{p}</a></li>" for p in PAGES)
                + "</ul></body></html>",
            )
            return
        name = path.lstrip("/")
        if name in ("releases.html", "status.html", "team.html"):
            try:
                with open(os.path.join(WWW, name), encoding="utf-8") as fh:
                    self._send(200, fh.read())
            except OSError:
                self._send(500, "page missing")
        else:
            self._send(404, "not found")

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8742
    HTTPServer(("127.0.0.1", port), Handler).serve_forever()
