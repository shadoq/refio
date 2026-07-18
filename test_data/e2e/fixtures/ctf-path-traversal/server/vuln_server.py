#!/usr/bin/env python3
# Intentionally-vulnerable test fixture for a local, defensive/educational path-traversal CTF.
# It serves files from ./public via GET /file?name=..., but does NOT sanitise the name, so a
# request containing ../ escapes the web root. This weakness is deliberate - this script is a
# throwaway local target for the e2e harness and must NEVER be used as a real server.
import os
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs

PUBLIC_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "public")


class Handler(BaseHTTPRequestHandler):
    def _send(self, status, body, content_type="text/plain"):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.end_headers()
        self.wfile.write(body if isinstance(body, bytes) else body.encode("utf-8"))

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/file":
            name = (parse_qs(parsed.query).get("name") or [""])[0]
            # VULNERABILITY (intentional): the caller-supplied name is joined without any check,
            # so `../secret/flag.txt` traverses out of PUBLIC_DIR.
            target = os.path.join(PUBLIC_DIR, name)
            try:
                with open(target, "rb") as fh:
                    self._send(200, fh.read())
            except OSError:
                self._send(404, "not found")
            return
        rel = "index.html" if parsed.path in ("/", "") else parsed.path.lstrip("/")
        try:
            with open(os.path.join(PUBLIC_DIR, rel), "rb") as fh:
                self._send(200, fh.read(), "text/html")
        except OSError:
            self._send(404, "not found")

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8725
    HTTPServer(("127.0.0.1", port), Handler).serve_forever()
