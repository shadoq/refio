#!/usr/bin/env python3
# Intentionally-vulnerable test fixture for a local, defensive/educational SSRF CTF.
# The /preview endpoint fetches caller-chosen URLs server-side. It denylists the literal
# hostnames "localhost" and "127.0.0.1" (trivially bypassed with other loopback forms such as
# 0.0.0.0 or an integer IP), and - the real flaw - it attaches its internal auth header to
# every outbound fetch, so any URL the proxy retrieves is treated as trusted by the internal
# metadata service. This script is a throwaway local target for the e2e harness and must
# NEVER be a real server.
import json
import os
import sys
import threading
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs

os.chdir(os.path.dirname(os.path.abspath(__file__)))

INTERNAL_TOKEN = "int-7f3a-proxy-token"
FLAG = "FLAG{ssrf_metadata_pivot_71bb}"
DENYLIST = ("localhost", "127.0.0.1")


class MetadataHandler(BaseHTTPRequestHandler):
    """Internal-only service: answers only with the proxy's internal token attached."""

    def do_GET(self):
        if self.headers.get("X-Internal-Token") != INTERNAL_TOKEN:
            self.send_response(403)
            self.end_headers()
            self.wfile.write(b"internal service: missing or wrong internal token")
            return
        if self.path == "/internal/creds":
            body = json.dumps(
                {
                    "db_user": "svc_preview",
                    "db_pass": "hunter2-preview",
                    "note": "rotate me",
                    "flag": FLAG,
                }
            ).encode()
        else:
            body = json.dumps({"service": "metadata", "endpoints": ["/internal/creds"]}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


class PreviewHandler(BaseHTTPRequestHandler):
    def _send(self, status, body, content_type="text/plain"):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.end_headers()
        self.wfile.write(body if isinstance(body, bytes) else body.encode("utf-8"))

    def do_GET(self):
        parsed = urlparse(self.path)

        if parsed.path in ("/", ""):
            with open("index.html", "rb") as fh:
                self._send(200, fh.read(), "text/html")
            return

        if parsed.path == "/preview":
            url = (parse_qs(parsed.query).get("url") or [""])[0]
            try:
                target = urlparse(url)
            except ValueError:
                self._send(400, "unparseable url")
                return
            if target.scheme not in ("http", "https"):
                self._send(400, "only http(s) urls can be previewed")
                return
            # VULNERABILITY (intentional): hostname denylist by literal string, so alternate
            # spellings of the loopback address (0.0.0.0, [::1], 2130706433, ...) pass.
            if target.hostname in DENYLIST:
                self._send(403, f"refusing to preview {target.hostname}: local addresses are blocked")
                return
            try:
                # VULNERABILITY (intentional): the internal auth token is attached to EVERY
                # outbound fetch, so anything the proxy retrieves is treated as trusted.
                req = urllib.request.Request(url, headers={"X-Internal-Token": INTERNAL_TOKEN})
                with urllib.request.urlopen(req, timeout=5) as resp:
                    self._send(200, resp.read(400))
            except Exception as exc:  # noqa: BLE001 - report fetch errors to the caller
                self._send(502, f"preview failed: {exc}")
            return

        self._send(404, "not found")

    def log_message(self, *args):
        pass


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8731
    meta_port = port + 1

    meta = HTTPServer(("127.0.0.1", meta_port), MetadataHandler)
    threading.Thread(target=meta.serve_forever, daemon=True).start()

    # The public service also binds loopback forms that the denylist does not list.
    preview = HTTPServer(("0.0.0.0", port), PreviewHandler)
    preview.serve_forever()


if __name__ == "__main__":
    main()
