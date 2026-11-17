#!/usr/bin/env python3
"""Session flow demo - a stdlib-only login service for the local e2e exercise.

Flow (the honest, browser-like way):
    GET /       - login form with a hidden CSRF token
    POST /login - form fields: username, password, csrf; on success sets a session cookie
    GET /report - protected; needs the session cookie; prints the exercise flag
    GET /health - readiness probe

This is a throwaway local target for the e2e harness and must NEVER be a real service.
"""
import hashlib
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs

USER = "analyst"
PASSWORD = "demo-password-9"
CSRF = hashlib.sha256(b"web-session-flow-csrf-seed").hexdigest()[:16]
SESSION = hashlib.sha256(b"session-flow-signing").hexdigest()[:24]
FLAG = "FLAG{web_session_flow_8c42}"

FORM = f"""<!DOCTYPE html>
<html><body>
<h1>Widget Hub analyst portal</h1>
<form action="/login" method="POST">
  <input type="hidden" name="csrf" value="{CSRF}">
  <label>username <input name="username"></label><br>
  <label>password <input name="password" type="password"></label><br>
  <button type="submit">sign in</button>
</form>
</body></html>"""


class Handler(BaseHTTPRequestHandler):
    def _send(self, status, body, ctype="text/html; charset=utf-8", cookie=None):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        if cookie:
            self.send_header("Set-Cookie", cookie)
        self.end_headers()
        self.wfile.write(body.encode("utf-8"))

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == "/":
            self._send(200, FORM)
        elif path == "/report":
            token = ""
            for pair in (self.headers.get("Cookie") or "").split(";"):
                k, _, v = pair.strip().partition("=")
                if k == "session":
                    token = v
            if token == SESSION:
                self._send(200, f"quarterly analyst report<br>{FLAG}")
            else:
                self._send(401, "sign in first")
        elif path == "/health":
            self._send(200, "ok", "text/plain")
        else:
            self._send(404, "not found")

    def do_POST(self):
        if self.path != "/login":
            self._send(404, "not found")
            return
        length = int(self.headers.get("Content-Length") or 0)
        form = parse_qs(self.rfile.read(length).decode("utf-8"))
        username = (form.get("username") or [""])[0]
        password = (form.get("password") or [""])[0]
        csrf = (form.get("csrf") or [""])[0]
        if csrf != CSRF:
            self._send(403, "bad csrf token")
        elif username == USER and password == PASSWORD:
            self._send(200, "login ok - session cookie set", cookie=f"session={SESSION}; Path=/")
        else:
            self._send(403, "bad credentials")

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8741
    HTTPServer(("127.0.0.1", port), Handler).serve_forever()
