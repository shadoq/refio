#!/usr/bin/env python3
"""Acceptance gate: boots the CURRENT (patched) server from server/app.py on an
ephemeral port and replays both attack paths plus legitimate traffic."""
import importlib.util
import json
import os
import re
import socket
import subprocess
import sys
import time
import urllib.request
import urllib.error

ROOT = os.path.dirname(os.path.abspath(__file__))
XSS_MARK = "zqx<svg>onload=alert(1)</svg>"


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def req(method, url, body=None, ctype="application/json"):
    data = json.dumps(body).encode() if body is not None and isinstance(body, dict) else (
        body.encode() if isinstance(body, str) else body)
    r = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        r.add_header("Content-Type", ctype)
    try:
        with urllib.request.urlopen(r, timeout=10) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def fail(msg):
    print("FAIL: " + msg)
    return 1


def main():
    # reports first (offline)
    try:
        web_md = open(os.path.join(ROOT, "attack/web.md"), encoding="utf-8").read()
        infra_md = open(os.path.join(ROOT, "attack/infra.md"), encoding="utf-8").read()
        fixes_md = open(os.path.join(ROOT, "report/fixes.md"), encoding="utf-8").read()
    except OSError as e:
        return fail("missing attack or report file: " + str(e))
    if len(re.findall(r"^web_finding = \S+ \| \S+$", web_md, re.M)) < 2:
        return fail("attack/web.md needs at least two `web_finding = <endpoint> | <class>` lines")
    if not re.search(r"^infra_finding = \S+ \| \S+$", infra_md, re.M):
        return fail("attack/infra.md needs `infra_finding = <endpoint> | <class>` lines")
    if not re.search(r"^fixed = \S+ \| ", fixes_md, re.M):
        return fail("report/fixes.md needs `fixed = <endpoint> | <how>` lines")

    port = free_port()
    proc = subprocess.Popen([sys.executable, os.path.join(ROOT, "server", "app.py"), str(port)],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        base = f"http://127.0.0.1:{port}"
        for _ in range(50):
            try:
                req("GET", base + "/login")
                break
            except OSError:
                time.sleep(0.1)
        # IDOR closed: bob's note (id 2) must NOT be readable
        code, body = req("GET", base + "/api/notes/2")
        if code not in (403, 404):
            return fail(f"IDOR still open: GET /api/notes/2 returned {code}")
        # debug env path closed
        code, body = req("GET", base + "/debug/env")
        if code not in (403, 404) or "NOTES_SECRET" in body:
            return fail(f"debug env leak still open: GET /debug/env returned {code}")
        code, body = req("GET", base + "/metrics")
        if code not in (403, 404):
            return fail(f"/metrics still exposed: returned {code}")
        # XSS escaped: reflected markup must be escaped
        code, body = req("POST", base + "/api/render", {"name": XSS_MARK})
        if code != 200:
            return fail(f"render endpoint broken: returned {code}")
        if XSS_MARK in body or "<svg>" in body:
            return fail("XSS reflection not escaped in /api/render")
        # legitimate traffic still works
        code, body = req("GET", base + "/login")
        if code != 200:
            return fail(f"login broken: returned {code}")
        code, body = req("GET", base + "/api/notes")
        if code != 200 or '"owner"' not in body:
            return fail(f"note list broken: returned {code}")
        code, body = req("GET", base + "/api/notes/1")
        if code != 200:
            return fail(f"own note read broken: GET /api/notes/1 returned {code}")
        code, body = req("POST", base + "/api/render", {"name": "world"})
        if code != 200 or "world" not in body:
            return fail(f"plain render broken: returned {code}")
        print("PASS: both attack paths closed, legitimate traffic intact, reports present")
        return 0
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()


if __name__ == "__main__":
    sys.exit(main())
