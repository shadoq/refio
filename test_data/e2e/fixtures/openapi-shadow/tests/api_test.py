#!/usr/bin/env python3
"""API tests for the UserDir service.

Usage:
    python3 tests/api_test.py              # starts a fresh server on a free port from
                                           # server/app.py and tests it
    python3 tests/api_test.py <base-url>   # tests an already-running instance

Exit code 0 means every check passed.
"""
import json
import os
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def wait_ready(base, timeout=10.0):
    """Wait until the port answers anything (the bug under test can shadow /health too,
    so readiness is a raw connection, not a 200 from a specific path)."""
    deadline = time.time() + timeout
    parsed = urllib.parse.urlsplit(base)
    host = parsed.hostname or "127.0.0.1"
    port = parsed.port or 80
    while time.time() < deadline:
        try:
            with socket.create_connection((host, port), timeout=2):
                return True
        except OSError:
            time.sleep(0.1)
    return False


def get(base, path):
    try:
        resp = urllib.request.urlopen(base + path, timeout=10)
        return resp.status, resp.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8")
    except (urllib.error.URLError, OSError) as exc:
        # a handler crash (the server dies mid-response) counts as a failed request
        return 0, f"request failed: {exc}"


def run_checks(base):
    failures = []

    # 1. The documented endpoint must serve the user list.
    status, text = get(base, "/api/v2/users")
    if status != 200:
        failures.append(f"GET /api/v2/users returned {status}, want 200: {text[:200]}")
    else:
        try:
            payload = json.loads(text)
            if payload.get("count") != 4 or payload.get("users", [{}])[0].get("username") != "ada":
                failures.append(f"GET /api/v2/users does not return the user list: {text[:200]}")
        except ValueError:
            failures.append(f"GET /api/v2/users did not return JSON: {text[:200]}")

    # 2. The dept filter of the documented endpoint must work.
    status, text = get(base, "/api/v2/users?dept=ops")
    if status != 200:
        failures.append(f"GET /api/v2/users?dept=ops returned {status}, want 200")
    else:
        try:
            if json.loads(text).get("count") != 1:
                failures.append("dept filter on /api/v2/users is broken")
        except ValueError:
            failures.append("dept filter on /api/v2/users did not return JSON")

    # 3. Unknown paths must still fall through to the catch-all 404 page.
    for path in ("/api/v3/anything", "/nope/nope"):
        status, text = get(base, path)
        if status != 404 or "not found" not in text:
            failures.append(f"GET {path} returned {status} ({text[:80]}), want the catch-all 404")

    # 4. The OpenAPI contract must still be served and still document the endpoint.
    status, text = get(base, "/openapi.json")
    if status != 200:
        failures.append(f"GET /openapi.json returned {status}, want 200")
    else:
        try:
            doc = json.loads(text)
            if "/api/v2/users" not in doc.get("paths", {}):
                failures.append("openapi.json no longer documents /api/v2/users")
        except ValueError:
            failures.append("openapi.json is not JSON")

    # 5. The health probe must answer.
    status, text = get(base, "/health")
    if status != 200:
        failures.append(f"GET /health returned {status}, want 200")

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
    print("PASS: 7 checks (v2 users list, dept filter, catch-all 404s, openapi served, health)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
