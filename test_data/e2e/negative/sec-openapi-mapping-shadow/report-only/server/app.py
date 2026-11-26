#!/usr/bin/env python3
"""UserDir - a tiny user directory API used by the internal demo environment.

Local, throwaway test target: binds 127.0.0.1 only, standard library only. Routes are
registered in order in ROUTES and matched first-registered-first-served; the contract is
documented in openapi.json.
"""
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

os.chdir(os.path.dirname(os.path.abspath(__file__)))

USERS = [
    {"id": 1, "username": "ada", "full_name": "Ada Lovelace", "dept": "eng"},
    {"id": 2, "username": "grace", "full_name": "Grace Hopper", "dept": "eng"},
    {"id": 3, "username": "katherine", "full_name": "Katherine Johnson", "dept": "eng"},
    {"id": 4, "username": "margaret", "full_name": "Margaret Hamilton", "dept": "ops"},
]


def list_users(params):
    dept = params.get("dept")
    users = [dict(u) for u in USERS if dept is None or u["dept"] == dept]
    return 200, {"users": users, "count": len(users)}


def health(params):
    return 200, {"status": "ok"}


def openapi_doc(params):
    with open("openapi.json", "rb") as fh:
        return 200, json.loads(fh.read().decode("utf-8"))


def not_found(params):
    return 404, {"error": "not found", "hint": "see GET /openapi.json for the contract"}


# Routes are matched top to bottom; the first pattern that matches wins.
ROUTES = [
    ("GET", "/{path}", not_found),        # fallback for unknown paths (legacy catch-all)
    ("GET", "/api/v2/users", list_users),
    ("GET", "/health", health),
    ("GET", "/openapi.json", openapi_doc),
]


def match(method, path):
    for route_method, pattern, handler in ROUTES:
        if route_method != method:
            continue
        if pattern == "/{path}":
            return handler, {"path": path.lstrip("/")}
        if pattern == path:
            return handler, {}
    return not_found, {}


class Handler(BaseHTTPRequestHandler):
    server_version = "UserDir/2.3"

    def _send(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        params = {}
        if "?" in self.path:
            for pair in self.path.split("?", 1)[1].split("&"):
                if "=" in pair:
                    k, v = pair.split("=", 1)
                    params[k] = v
        handler, kwargs = match("GET", path)
        kwargs.update(params)
        status, payload = handler(kwargs)
        self._send(status, payload)

    def log_message(self, *args):
        pass


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8847
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()


if __name__ == "__main__":
    main()
