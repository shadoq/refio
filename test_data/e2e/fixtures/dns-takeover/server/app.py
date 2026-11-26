#!/usr/bin/env python3
"""EdgeStats - the main web app of the demo environment.

Local, throwaway test target: binds 127.0.0.1 only, standard library only. It routes on
the Host header: app.example.test is the real app; the routing table in config/vhosts.json
decides which hosts this deployment answers for.
"""
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

os.chdir(os.path.dirname(os.path.abspath(__file__)))


def load_vhosts(path="config/vhosts.json"):
    with open(path, "r", encoding="utf-8") as fh:
        table = json.load(fh)
    return table.get("vhosts", {})


VHOSTS = load_vhosts()


class Handler(BaseHTTPRequestHandler):
    server_version = "EdgeStats/3.1"

    def _send(self, status, text, content_type="text/html"):
        data = text.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _host(self):
        return (self.headers.get("Host") or "").split(":")[0].strip().lower()

    def do_GET(self):
        host = self._host()
        route = VHOSTS.get(host)

        if route is None:
            self._send(404, "<html><body><h1>404 no such site</h1></body></html>")
            return

        if route.get("app") == "main":
            if self.path == "/":
                self._send(200, "<html><body><h1>EdgeStats</h1>"
                                 "<p>Daily edge statistics for the demo environment.</p>"
                                 "<ul><li>today: 143,205 requests</li>"
                                 "<li>p95 latency: 41 ms</li></ul></body></html>")
                return
            if self.path == "/api/stats":
                self._send(200, '{"requests_today": 143205, "p95_ms": 41}',
                           content_type="application/json")
                return
            self._send(404, "<html><body><h1>404</h1></body></html>")
            return

        if route.get("app") == "legacy-takeover":
            # Serves the old marketing page that was left behind on this box.
            self._send(200, "<html><body><h1>Old App</h1>"
                             "<p>FLAG{stale-vhost-takeover}</p>"
                             "<p>This legacy site is served to whoever resolves here.</p>"
                             "</body></html>")
            return

        self._send(404, "<html><body><h1>404 no such site</h1></body></html>")

    def log_message(self, *args):
        pass


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8849
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()


if __name__ == "__main__":
    main()
