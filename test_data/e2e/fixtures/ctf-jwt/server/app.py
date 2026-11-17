#!/usr/bin/env python3
# Intentionally-vulnerable test fixture for a local, defensive/educational JWT CTF.
# The service signs HS256 JWTs with a secret loaded from config/dev.env - a file that was
# committed to the project by mistake, which is the weakness this exercise demonstrates.
# This script is a throwaway local target for the e2e harness and must NEVER be a real server.
import hashlib
import hmac
import json
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs

os.chdir(os.path.dirname(os.path.abspath(__file__)))

FLAG = "FLAG{jwt_weak_secret_f0rg3ry_7b1d}"


def load_secret():
    with open("config/dev.env", "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line.startswith("JWT_SECRET="):
                return line.split("=", 1)[1].strip()
    raise RuntimeError("JWT_SECRET missing from config/dev.env")


SECRET = load_secret()


def b64url(data):
    import base64

    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def b64url_decode(data):
    import base64

    pad = "=" * (-len(data) % 4)
    return base64.urlsafe_b64decode(data + pad)


def make_token(sub, role):
    header = b64url(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
    payload = b64url(
        json.dumps(
            {"sub": sub, "role": role, "iat": int(time.time()), "exp": int(time.time()) + 3600}
        ).encode()
    )
    signing_input = f"{header}.{payload}".encode()
    sig = b64url(hmac.new(SECRET.encode(), signing_input, hashlib.sha256).digest())
    return f"{header}.{payload}.{sig}"


def verify_token(token):
    try:
        header_b64, payload_b64, sig_b64 = token.split(".")
        signing_input = f"{header_b64}.{payload_b64}".encode()
        expected = b64url(hmac.new(SECRET.encode(), signing_input, hashlib.sha256).digest())
        if not hmac.compare_digest(expected, sig_b64):
            return None
        header = json.loads(b64url_decode(header_b64))
        if header.get("alg") != "HS256":
            return None
        payload = json.loads(b64url_decode(payload_b64))
        if int(payload.get("exp", 0)) < time.time():
            return None
        return payload
    except Exception:
        return None


class Handler(BaseHTTPRequestHandler):
    def _send(self, status, body, content_type="text/plain"):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.end_headers()
        self.wfile.write(body if isinstance(body, bytes) else body.encode("utf-8"))

    def do_GET(self):
        parsed = urlparse(self.path)
        qs = parse_qs(parsed.query)

        if parsed.path in ("/", ""):
            with open("index.html", "rb") as fh:
                self._send(200, fh.read(), "text/html")
            return

        if parsed.path == "/login":
            user = (qs.get("user") or ["guest"])[0]
            if user == "admin":
                self._send(403, "admin logins are disabled on this endpoint")
                return
            token = make_token(user, "user")
            self._send(200, json.dumps({"token": token, "note": "role is fixed at issue time"}))
            return

        if parsed.path == "/protected":
            token = (qs.get("token") or [""])[0]
            payload = verify_token(token)
            if payload is None:
                self._send(401, "invalid or expired token")
            else:
                self._send(200, f"hello {payload.get('sub')} (role={payload.get('role')})")
            return

        if parsed.path == "/admin":
            token = (qs.get("token") or [""])[0]
            payload = verify_token(token)
            if payload is None:
                self._send(401, "invalid or expired token")
            elif payload.get("role") != "admin":
                self._send(403, f"role {payload.get('role')!r} is not allowed here")
            else:
                self._send(200, f"welcome, administrator {payload.get('sub')}. {FLAG}")
            return

        self._send(404, "not found")

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8730
    HTTPServer(("127.0.0.1", port), Handler).serve_forever()
