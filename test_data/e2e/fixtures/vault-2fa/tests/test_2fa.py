#!/usr/bin/env python3
"""Tests for the KeyVault two-step login.

Usage:
    python3 tests/test_2fa.py              # starts a fresh server on a free port from
                                           # server/app.py and tests it
    python3 tests/test_2fa.py <base-url>   # tests an already-running instance

Exit code 0 means every check passed.
"""
import base64
import hashlib
import hmac
import json
import os
import socket
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

USERS = {
    "demo": {"password": "demo-pony-lantern-42", "totp_seed": "JBSWY3DPEHPK3PXP"},
    "ops": {"password": "ops-harbor-quilt-77", "totp_seed": "MFRGGZDFMZTWQ2LK"},
}


def totp(seed_b32, step=30, digits=6, at=None):
    if at is None:
        at = int(time.time())
    counter = at // step
    key = base64.b32decode(seed_b32 + "=" * (-len(seed_b32) % 8))
    digest = hmac.new(key, struct.pack(">Q", counter), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    code = (struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7FFFFFFF) % (10 ** digits)
    return str(code).zfill(digits)


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def wait_ready(base, timeout=10.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            urllib.request.urlopen(base + "/", timeout=2).read()
            return True
        except Exception:
            time.sleep(0.1)
    return False


def post(base, path, payload, bearer=None):
    headers = {"Content-Type": "application/json"}
    if bearer:
        headers["Authorization"] = "Bearer " + bearer
    req = urllib.request.Request(base + path, data=json.dumps(payload).encode("utf-8"),
                                 headers=headers, method="POST")
    try:
        resp = urllib.request.urlopen(req, timeout=10)
        return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        try:
            return exc.code, json.loads(exc.read().decode("utf-8"))
        except ValueError:
            return exc.code, {}


def get(base, path, bearer=None):
    headers = {}
    if bearer:
        headers["Authorization"] = "Bearer " + bearer
    req = urllib.request.Request(base + path, headers=headers)
    try:
        resp = urllib.request.urlopen(req, timeout=10)
        return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        try:
            return exc.code, json.loads(exc.read().decode("utf-8"))
        except ValueError:
            return exc.code, {}


def run_checks(base):
    failures = []

    # --- happy path: login, verify, vault ---
    status, body = post(base, "/login", {"user": "demo", "password": USERS["demo"]["password"]})
    if status != 200 or "session" not in body:
        failures.append(f"login for demo failed: {status} {body}")
        return failures
    session = body["session"]

    status, body = get(base, "/vault", bearer=session)
    if status != 403:
        failures.append(f"unverified session reached the vault: {status} {body}")

    status, body = post(base, "/verify", {"session": session,
                                          "token": totp(USERS["demo"]["totp_seed"])})
    if status != 200 or not body.get("verified"):
        failures.append(f"verify for demo failed: {status} {body}")

    status, body = get(base, "/vault", bearer=session)
    if status != 200 or "vault" not in body:
        failures.append(f"verified session cannot read the vault: {status} {body}")

    # --- session reset: a second login must invalidate the first session's state and
    #     require verification again; the first (now stale) session must be rejected ---
    status, body = post(base, "/login", {"user": "demo", "password": USERS["demo"]["password"]})
    if status != 200 or "session" not in body:
        failures.append(f"second login for demo failed: {status} {body}")
        return failures
    session2 = body["session"]

    # the old session must no longer open the vault
    status, body = get(base, "/vault", bearer=session)
    if status != 401:
        failures.append(f"stale session still accepted after relogin: {status} {body}")

    # the new session must NOT inherit verified state
    status, body = get(base, "/vault", bearer=session2)
    if status != 403:
        failures.append(f"fresh session skipped 2FA after relogin: {status} {body}")

    # completing verification on the new session works and then opens the vault
    status, body = post(base, "/verify", {"session": session2,
                                          "token": totp(USERS["demo"]["totp_seed"])})
    if status != 200:
        failures.append(f"verify after relogin failed: {status} {body}")
    status, body = get(base, "/vault", bearer=session2)
    if status != 200:
        failures.append(f"verified relogin session cannot read the vault: {status} {body}")

    # --- a second user must be handled independently ---
    status, body = post(base, "/login", {"user": "ops", "password": USERS["ops"]["password"]})
    if status != 200 or "session" not in body:
        failures.append(f"login for ops failed: {status} {body}")
        return failures
    session_ops = body["session"]

    status, body = get(base, "/vault", bearer=session_ops)
    if status != 403:
        failures.append(f"ops session skipped 2FA (state keyed too narrowly?): {status} {body}")

    status, body = post(base, "/verify", {"session": session_ops,
                                          "token": totp(USERS["ops"]["totp_seed"])})
    if status != 200:
        failures.append(f"verify for ops failed: {status} {body}")
    status, body = get(base, "/vault", bearer=session_ops)
    if status != 200:
        failures.append(f"verified ops session cannot read the vault: {status} {body}")

    # a second login for ops must equally require verification again
    status, body = post(base, "/login", {"user": "ops", "password": USERS["ops"]["password"]})
    if status != 200 or "session" not in body:
        failures.append(f"second login for ops failed: {status} {body}")
        return failures
    session_ops2 = body["session"]
    status, body = get(base, "/vault", bearer=session_ops2)
    if status != 403:
        failures.append(f"ops session skipped 2FA after relogin: {status} {body}")
    status, body = get(base, "/vault", bearer=session_ops)
    if status != 401:
        failures.append(f"stale ops session still accepted after relogin: {status} {body}")

    # and demo's verified session still works
    status, body = get(base, "/vault", bearer=session2)
    if status != 200:
        failures.append(f"demo session broken by ops login: {status} {body}")

    # --- wrong password and garbage tokens stay rejected ---
    status, body = post(base, "/login", {"user": "demo", "password": "wrong"})
    if status != 401:
        failures.append(f"wrong password accepted: {status} {body}")
    status, body = post(base, "/verify", {"session": session2, "token": "000000"})
    if status == 200:
        failures.append("garbage TOTP token accepted")

    return failures


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("-")]
    proc = None
    if args:
        base = args[0].rstrip("/")
    else:
        port = free_port()
        base = f"http://127.0.0.1:{port}"
        proc = subprocess.Popen([sys.executable, os.path.join(ROOT, "server", "app.py"),
                                 str(port)],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        if not wait_ready(base):
            print("FAIL: server did not become ready on " + base)
            return 1
        failures = run_checks(base)
    finally:
        if proc is not None:
            proc.terminate()
            proc.wait(timeout=10)

    if failures:
        print("FAIL")
        for f in failures:
            print(" - " + f)
        return 1
    print("PASS: 14 checks (happy path, relogin resets 2FA and invalidates old session, "
          "per-user isolation incl. a second account, bad credentials rejected)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
