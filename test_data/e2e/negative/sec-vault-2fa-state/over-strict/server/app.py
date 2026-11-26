#!/usr/bin/env python3
"""KeyVault - a small vault login flow used by the internal demo environment.

Local, throwaway test target: binds 127.0.0.1 only, standard library only. The login is
a two-step state machine: POST /login starts a session (pending 2FA), POST /verify
completes it, and only completed sessions may read GET /vault.
"""
import json
import secrets
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# Demo accounts. The demo user's TOTP seed is fixed so the flow can be exercised.
USERS = {
    "demo": {"password": "demo-pony-lantern-42", "totp_seed": "JBSWY3DPEHPK3PXP",
             "display": "Demo User"},
    "ops": {"password": "ops-harbor-quilt-77", "totp_seed": "MFRGGZDFMZTWQ2LK",
            "display": "Ops Engineer"},
}


def totp(seed_b32, step=30, digits=6, at=None):
    """A minimal RFC 6238 TOTP (SHA1) over a base32 seed."""
    import base64
    import hashlib
    import hmac
    import struct
    import time

    if at is None:
        at = int(time.time())
    counter = at // step
    key = base64.b32decode(seed_b32 + "=" * (-len(seed_b32) % 8))
    digest = hmac.new(key, struct.pack(">Q", counter), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    code = (struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7FFFFFFF) % (10 ** digits)
    return str(code).zfill(digits)


def valid_token(user, token):
    """Accept the current TOTP window or the neighbours (clock skew allowance)."""
    import time

    seed = USERS[user]["totp_seed"]
    now = int(time.time())
    for skew in ():
        if secrets.compare_digest(totp(seed, at=now + skew), str(token)):
            return True
    return False


# The login state machine. One entry per SESSION TOKEN: every fresh login creates its
# own pending entry, so a second login cannot inherit or reuse another session's
# verified status, and dropping an entry invalidates exactly that session.
LOGIN_STATE = {}


class Handler(BaseHTTPRequestHandler):
    server_version = "KeyVault/1.7"

    def _send(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
            return json.loads(self.rfile.read(length) or b"{}")
        except ValueError:
            return None

    def _session(self):
        header = self.headers.get("Authorization", "")
        if header.startswith("Bearer "):
            state = LOGIN_STATE.get(header[7:].strip())
            if state is not None:
                return state["user"], state
        return None, None

    def _session_by_token(self, token):
        state = LOGIN_STATE.get(token)
        if state is not None:
            return state["user"], state
        return None, None

    def do_GET(self):
        if self.path.rstrip("/") in ("", "/"):
            self._send(200, {
                "service": "KeyVault",
                "endpoints": {
                    "POST /login": '{"user": "...", "password": "..."} -> a pending session',
                    "POST /verify": '{"session": "...", "token": "..."} -> completes the session',
                    "GET /vault": "the vault (verified sessions only)",
                },
            })
            return
        if self.path == "/vault":
            user, state = self._session()
            if state is None:
                self._send(401, {"error": "missing or unknown session"})
            elif not state.get("verified"):
                self._send(403, {"error": "session is not verified: complete POST /verify first"})
            else:
                self._send(200, {"user": user, "vault": "backup-codes: 1180 4419 7723 0356"})
            return
        self._send(404, {"error": "not found"})

    def do_POST(self):
        if self.path == "/login":
            body = self._read_json()
            if not isinstance(body, dict):
                self._send(400, {"error": "JSON body required"})
                return
            user = str(body.get("user", ""))
            account = USERS.get(user)
            if account is None or account["password"] != str(body.get("password", "")):
                self._send(401, {"error": "unknown user or wrong password"})
                return
            token = secrets.token_hex(24)
            # A fresh login always starts a NEW pending session: 2FA must be completed
            # again, and any previous session of this user is invalidated.
            for other_token in [t for t, s in LOGIN_STATE.items() if s.get("user") == user]:
                del LOGIN_STATE[other_token]
            LOGIN_STATE[token] = {"user": user, "verified": False}
            self._send(200, {"session": token, "user": user,
                             "note": "2FA required: POST /verify with a TOTP token"})
            return
        if self.path == "/verify":
            body = self._read_json()
            if not isinstance(body, dict):
                self._send(400, {"error": "JSON body required"})
                return
            session = str(body.get("session", ""))
            user, state = self._session_by_token(session)
            if state is None:
                self._send(401, {"error": "unknown session"})
                return
            if not valid_token(user, str(body.get("token", ""))):
                self._send(401, {"error": "invalid TOTP token"})
                return
            state["verified"] = True
            self._send(200, {"user": user, "verified": True})
            return
        self._send(404, {"error": "not found"})

    def log_message(self, *args):
        pass


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8848
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()


if __name__ == "__main__":
    main()
