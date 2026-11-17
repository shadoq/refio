#!/usr/bin/env python3
# Intentionally-vulnerable test fixture for a local, defensive/educational command-injection CTF.
# The /ping endpoint pastes the caller-supplied host into a shell command line without any
# sanitisation, so shell metacharacters (;, $(...), backticks) execute arbitrary commands. This
# weakness is deliberate - this script is a throwaway local target for the e2e harness and must
# NEVER be used as a real server.
import os
import subprocess
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs

os.chdir(os.path.dirname(os.path.abspath(__file__)))


class Handler(BaseHTTPRequestHandler):
    def _send(self, status, body, content_type="text/plain"):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.end_headers()
        self.wfile.write(body if isinstance(body, bytes) else body.encode("utf-8"))

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/ping":
            host = (parse_qs(parsed.query).get("host") or [""])[0]
            # VULNERABILITY (intentional): the caller-supplied host is pasted into a shell
            # command line, so `;`, `$()` and backticks run arbitrary commands.
            cmd = "ping -c 1 " + host
            try:
                proc = subprocess.run(
                    cmd, shell=True, capture_output=True, text=True, timeout=6
                )
                self._send(200, proc.stdout + proc.stderr)
            except subprocess.TimeoutExpired:
                self._send(504, "ping timed out")
            return
        rel = "index.html" if parsed.path in ("/", "") else parsed.path.lstrip("/")
        try:
            with open(rel, "rb") as fh:
                self._send(200, fh.read(), "text/html")
        except OSError:
            self._send(404, "not found")

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8726
    HTTPServer(("127.0.0.1", port), Handler).serve_forever()
