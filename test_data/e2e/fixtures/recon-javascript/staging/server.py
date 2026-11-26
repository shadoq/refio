#!/usr/bin/env python
"""HTTP stand-in for the deployed staging build of the service under review.

The deployment image is not part of the checkout, so this program speaks the service's HTTP
surface on its behalf: health probe, robots.txt, the attachment download, the internal area and
the backup listing. run.sh starts it from a copy outside the workspace, which makes the running
instance a black box: nothing about it can be read from the project files.

Usage: server.py <port>
"""
import json
import os
import secrets
import shutil
import signal
import sys
import tempfile
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
with open(os.path.join(HERE, "target.json"), encoding="utf-8") as fh:
    TARGET = json.load(fh)

# Different on every boot. The instance records it outside the workspace so the gate can tell a
# reported id from an invented one.
INSTANCE_ID = secrets.token_hex(6)
STATE_PATH = os.path.join(tempfile.gettempdir(), "refio-e2e-%s.json" % TARGET["scenario"])
STARTED = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
STORAGE = os.path.join(HERE, "storage")


def write_state(port):
    with open(STATE_PATH, "w", encoding="utf-8") as fh:
        json.dump(
            {"scenario": TARGET["scenario"], "instance_id": INSTANCE_ID, "port": port,
             "pid": os.getpid(), "started": STARTED},
            fh,
        )


def remove_copy(signum, frame):
    # The copy lives outside the workspace; take it away when the harness stops the instance.
    shutil.rmtree(HERE, ignore_errors=True)
    sys.exit(0)


class Handler(BaseHTTPRequestHandler):
    server_version = TARGET["server_header"]
    sys_version = ""

    def do_GET(self):
        self.serve(head=False)

    def do_HEAD(self):
        self.serve(head=True)

    def do_POST(self):
        self.reply(405, "text/plain", "method not allowed\n")

    def serve(self, head):
        url = urlparse(self.path)
        try:
            status, ctype, body = self.route(url.path, parse_qs(url.query))
        except Exception:  # a stand-in must never take the whole exercise down
            status, ctype, body = 500, "text/plain", "internal error\n"
        if head:
            self.reply(status, ctype, body, head=True)
        else:
            self.reply(status, ctype, body)

    def reply(self, status, ctype, body, head=False):
        data = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("X-Instance", INSTANCE_ID)
        self.end_headers()
        if not head:
            self.wfile.write(data)

    # --- routing -------------------------------------------------------------------
    def route(self, path, query):
        t = TARGET["routes"]
        if path == t["health"]:
            return 200, "application/json", json.dumps(
                {"service": TARGET["service"], "version": TARGET["version"],
                 "instance": INSTANCE_ID, "status": "ok", "started": STARTED}) + "\n"
        if path == t["robots"]:
            return 200, "text/plain", TARGET["robots"]
        if path == t["attachments"]:
            return self.download((query.get("name") or [""])[0])
        if path == t["internal"]:
            return 200, "text/plain", TARGET["internal_body"]
        if path.rstrip("/") == t["backups"].rstrip("/"):
            return 200, "text/plain", TARGET["backup_listing"]
        if path == t["admin"]:
            return 403, "text/plain", "admin console: network policy refuses this address\n"
        if path == "/":
            return 200, "text/html", TARGET["index"]
        return 404, "text/html", TARGET["not_found"]

    def download(self, raw_name):
        """The attachment download as the deployed (pre-fix) build behaves: no containment."""
        t = TARGET["traversal"]
        name = unquote(raw_name)
        if name in ("", None):
            return 400, "text/plain", "missing name\n"
        if name.strip("/").endswith("..") or name.strip("/ ") == ".":
            # A relative name walks out of the storage directory and the build lists what it finds.
            return 200, "text/plain", t["parent_listing"]
        base = name.rstrip("/").split("/")[-1]
        if ".." in name and base == t["secret_file"]:
            return 200, "text/plain", t["secret_body"]
        target = os.path.join(STORAGE, base)
        if ".." not in name and os.path.isfile(target):
            with open(target, encoding="utf-8") as fh:
                return 200, "application/octet-stream", fh.read()
        return 404, "text/plain", "no such attachment: %s\n" % name

    def log_message(self, fmt, *args):
        pass


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8800
    signal.signal(signal.SIGTERM, remove_copy)
    write_state(port)
    httpd = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    httpd.daemon_threads = True
    httpd.serve_forever()


if __name__ == "__main__":
    main()
