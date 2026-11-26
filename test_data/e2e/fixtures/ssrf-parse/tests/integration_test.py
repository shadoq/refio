#!/usr/bin/env python3
"""Integration tests for the LinkFetch server.

Usage:
    python3 tests/integration_test.py              # starts a fresh server on a free
                                                   # port from server/app.py and tests it
    python3 tests/integration_test.py <base-url>   # tests an already-running instance

Exit code 0 means every check passed.
"""
import json
import os
import socket
import subprocess
import sys
import time
import urllib.error
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
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            urllib.request.urlopen(base + "/health", timeout=2).read()
            return True
        except Exception:
            time.sleep(0.1)
    return False


def fetch(base, url):
    req = urllib.request.Request(base.rstrip("/") + "/fetch",
                                 data=json.dumps({"url": url}).encode("utf-8"),
                                 headers={"Content-Type": "application/json"},
                                 method="POST")
    try:
        resp = urllib.request.urlopen(req, timeout=10)
        return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        return exc.code, json.loads(exc.read().decode("utf-8"))


def run_checks(base):
    failures = []

    # 1. Legitimate allowlisted fetches must keep working.
    status, body = fetch(base, "http://api.internal/")
    if status != 200 or "api.internal" not in body.get("body", ""):
        failures.append(f"legit fetch of api.internal failed: {status} {body}")

    status, body = fetch(base, "http://metadata.internal/")
    if status != 200 or "metadata.internal" not in body.get("body", ""):
        failures.append(f"legit fetch of metadata.internal failed: {status} {body}")

    # 2. Direct fetch of the link-local metadata host must be blocked.
    status, body = fetch(base, "http://169.254.169.254/")
    if status != 403:
        failures.append(f"direct metadata fetch was not blocked: {status} {body}")

    # 3. Redirect bypass: api.internal/redirect redirects to the metadata host; the
    #    fetcher must refuse to follow it onto a blocked host.
    status, body = fetch(base, "http://api.internal/redirect?to=http://169.254.169.254/")
    if status != 403:
        failures.append(f"redirect to metadata host was not blocked: {status} {body}")
    elif "instance-metadata" in body.get("body", ""):
        failures.append("redirect bypass reached the instance metadata service")

    # 4. Userinfo bypass: http://user@api.internal@169.254.169.254/ - a naive parser
    #    sees api.internal, but the real destination authority is the metadata host.
    #    Must be blocked.
    status, body = fetch(base, "http://user@api.internal@169.254.169.254/")
    if status != 403:
        failures.append(f"userinfo bypass was not blocked: {status} {body}")
    elif "instance-metadata" in body.get("body", ""):
        failures.append("userinfo bypass reached the instance metadata service")

    # 5. The .admin suffix stays blocked, directly and through the redirector.
    for url in ("http://keys.admin/", "http://api.internal/redirect?to=http://keys.admin/"):
        status, body = fetch(base, url)
        if status != 403:
            failures.append(f"blocked .admin host was not refused ({url}): {status} {body}")

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
    print("PASS: 8 checks (legit fetches, direct block, redirect block, userinfo block, .admin blocks)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
