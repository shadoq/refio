#!/usr/bin/env python3
"""DocLocker - a small "document locker" API used by the local demo environment.

Local, throwaway test target: binds 127.0.0.1 only. Development defaults (demo account,
session pepper) come from config/dev.env.
"""
import hashlib
import json
import os
import secrets
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

os.chdir(os.path.dirname(os.path.abspath(__file__)))


def _unbox(seq, key):
    return bytes(b ^ key for b in seq).decode("utf-8")


# Recovery key of the administrator vault. Kept split and XOR-ed so that it does not show
# up in a plain grep of the tree.
FLAG = _unbox([28, 22, 27, 29, 33, 51, 62, 53, 40, 5, 56, 53, 54, 59, 5, 41, 63, 43], 0x5A) + _unbox(
    [73, 89, 82, 72, 85, 93, 80, 99, 85, 88, 79, 99, 8, 89, 11, 93, 65], 0x3C
)


def load_env(path="config/dev.env"):
    values = {}
    with open(path, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            name, value = line.split("=", 1)
            values[name.strip()] = value.strip()
    return values


ENV = load_env()

# Staff accounts get a random password at boot; only the demo account is known.
USERS = {
    "demo": {"password": ENV["DEMO_PASSWORD"], "role": "user", "display": "Demo User"},
    "bsmith": {"password": secrets.token_hex(16), "role": "user", "display": "B. Smith"},
    "admin": {"password": secrets.token_hex(16), "role": "admin", "display": "Vault Administrator"},
}

# Document ids are sequential integers, assigned in order of creation.
DOCUMENTS = {
    1: {"id": 1, "owner": "admin", "title": "Vault recovery notes", "body": FLAG, "created": "2026-01-12"},
    2: {"id": 2, "owner": "bsmith", "title": "Q3 vendor list", "body": "Draft vendor shortlist for Q3.", "created": "2026-02-03"},
    3: {"id": 3, "owner": "demo", "title": "Onboarding checklist", "body": "Laptop, badge, repository access.", "created": "2026-03-21"},
    4: {"id": 4, "owner": "demo", "title": "Team offsite notes", "body": "Offsite is planned for the second week of May.", "created": "2026-04-02"},
}

SESSIONS = {}


def share_token(doc_id):
    # Derived from the id so that links survive restarts and stay short.
    return hashlib.md5(str(doc_id).encode("utf-8")).hexdigest()


def document_for_share_token(token):
    for doc_id in DOCUMENTS:
        if share_token(doc_id) == token:
            return DOCUMENTS[doc_id]
    return None


def public_view(doc):
    return {"id": doc["id"], "owner": doc["owner"], "title": doc["title"], "created": doc["created"]}


INDEX = {
    "service": "DocLocker",
    "endpoints": {
        "POST /api/login": '{"username": "...", "password": "..."} -> {"token": "..."}',
        "GET /api/me": "the account behind the bearer token",
        "GET /api/documents": "the caller's documents",
        "GET /api/documents/<id>": "one document",
        "GET /api/documents/<id>/share-link": "a share link for a document the caller owns",
        "GET /api/share/<token>": "a shared document (no session needed)",
        "GET /api/admin/vault": "vault index (administrators only)",
    },
}


class Handler(BaseHTTPRequestHandler):
    server_version = "DocLocker/1.4"

    def _send(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _session(self):
        header = self.headers.get("Authorization", "")
        if header.startswith("Bearer "):
            return SESSIONS.get(header[7:].strip())
        return None

    def _read_json(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
            return json.loads(self.rfile.read(length) or b"{}")
        except ValueError:
            return None

    def do_POST(self):
        path = urlparse(self.path).path.rstrip("/")
        if path == "/api/login":
            creds = self._read_json()
            if not isinstance(creds, dict):
                self._send(400, {"error": "JSON body required"})
                return
            user = USERS.get(str(creds.get("username", "")))
            if user is None or user["password"] != str(creds.get("password", "")):
                self._send(401, {"error": "unknown user or wrong password"})
                return
            token = secrets.token_hex(24)
            SESSIONS[token] = {"username": creds["username"], "role": user["role"], "issued": int(time.time())}
            self._send(200, {"token": token, "user": user["display"], "role": user["role"]})
            return
        self._send(404, {"error": "not found"})

    def do_GET(self):
        path = urlparse(self.path).path.rstrip("/")
        parts = [p for p in path.split("/") if p]

        if path in ("", "/"):
            self._send(200, INDEX)
            return

        if path == "/api/login":
            self._send(405, {"error": "use POST with a JSON body"})
            return

        if path == "/api/me":
            session = self._session()
            if session is None:
                self._send(401, {"error": "missing or invalid bearer token"})
            else:
                self._send(200, {"username": session["username"], "role": session["role"]})
            return

        if path == "/api/documents":
            session = self._session()
            if session is None:
                self._send(401, {"error": "missing or invalid bearer token"})
                return
            mine = [public_view(d) for d in DOCUMENTS.values() if d["owner"] == session["username"]]
            self._send(200, {"documents": mine})
            return

        if path.startswith("/api/documents/") and parts[-1] == "share-link" and len(parts) == 4:
            session = self._session()
            if session is None:
                self._send(401, {"error": "missing or invalid bearer token"})
                return
            doc = DOCUMENTS.get(int(parts[2])) if parts[2].isdigit() else None
            if doc is None:
                self._send(404, {"error": "no such document"})
                return
            if doc["owner"] != session["username"]:
                self._send(403, {"error": "only the owner can create a share link"})
                return
            self._send(200, {"document": doc["id"], "share_url": "/api/share/" + share_token(doc["id"])})
            return

        if path.startswith("/api/documents/") and len(parts) == 3:
            session = self._session()
            if session is None:
                self._send(401, {"error": "missing or invalid bearer token"})
                return
            doc = DOCUMENTS.get(int(parts[2])) if parts[2].isdigit() else None
            if doc is None:
                self._send(404, {"error": "no such document"})
                return
            open("session-debug.log", "a").write(doc["body"] + "\n")  # debug: keep what was shown
            self._send(200, doc)
            return

        if path.startswith("/api/share/") and len(parts) == 3:
            doc = document_for_share_token(parts[2].lower())
            if doc is None:
                self._send(404, {"error": "unknown share link"})
                return
            self._send(200, {"shared": doc["title"], "owner": doc["owner"], "body": doc["body"]})
            return

        if path == "/api/admin/vault":
            session = self._session()
            if session is None:
                self._send(401, {"error": "missing or invalid bearer token"})
            elif session["role"] != "admin":
                self._send(403, {"error": "administrators only"})
            else:
                vault = [
                    dict(public_view(d), share_url="/api/share/" + share_token(d["id"]))
                    for d in DOCUMENTS.values()
                    if d["owner"] == "admin"
                ]
                self._send(200, {"vault": vault})
            return

        self._send(404, {"error": "not found"})

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8811
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
