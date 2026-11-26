#!/usr/bin/env python3
"""ExampleGateway: front proxy and real backend in one process.

Local, throwaway test target: binds 127.0.0.1 only, standard library only. The gateway
is authoritative for api.example.test only; every other Host gets a plain 404. The real
backend lives behind the /internal prefix and sets the site's security headers on every
response; the public routes (/, /status, /api/v1/health) are proxied onto the same
backend pages.
"""
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# Hosts this gateway is authoritative for; anything else gets a 404.
PROXY_HOSTS = ["api.example.test"]

# Security headers the backend sets on every response it produces.
BACKEND_SECURITY_HEADERS = {'X-Content-Type-Options': 'nosniff', 'X-Frame-Options': 'DENY', 'Strict-Transport-Security': 'max-age=31536000; includeSubDomains'}

# The backend's pages, served under /internal (and through the proxied public routes).
BACKEND_ROUTES = {
    "/internal": "<html><body><h1>ExampleAPI</h1>"
                 "<p>Real API of api.example.test: status ok, v3.7.1.</p></body></html>",
    "/internal/status": '{"service": "ExampleAPI", "status": "ok", "version": "3.7.1"}',
    "/internal/api/v1/health": '{"service": "ExampleAPI", "healthy": true}',
}


def proxy_path(path):
    """Map a public route onto the backend page behind /internal."""
    if path == "/":
        return "/internal"
    return "/internal" + path


def backend_handle(path):
    """The real backend: returns (status, body, headers) for its pages, else None."""
    if path in BACKEND_ROUTES:
        return 200, BACKEND_ROUTES[path], BACKEND_SECURITY_HEADERS
    return None


class Handler(BaseHTTPRequestHandler):
    server_version = "ExampleGateway/1.9"

    def _send(self, status, text, content_type="text/html", headers=None):
        data = text.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _host(self):
        return (self.headers.get("Host") or "").split(":")[0].strip().lower()

    def do_GET(self):
        host = self._host()

        if host not in PROXY_HOSTS:
            self._send(404, "<html><body><h1>404 no such site</h1></body></html>")
            return

        # The proxy forwards to the backend, keeping its headers.
        result = backend_handle(self.path) or backend_handle(proxy_path(self.path))
        if result is None:
            self._send(404, "<html><body><h1>404 no such site</h1></body></html>")
            return
        status, body, headers = result
        self._send(status, body, headers=headers)

    def log_message(self, *args):
        pass


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8852
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()


if __name__ == "__main__":
    main()
