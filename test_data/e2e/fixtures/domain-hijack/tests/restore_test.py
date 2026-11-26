#!/usr/bin/env python3
"""Live gate for the hijack restore: boots the gateway itself, then asserts on it.

  1. direct requests to Host api.example.test reach the BACKEND (the ExampleAPI pages,
     not the attacker page) on the proxied public routes AND under /internal, with all
     three security headers the backend sets, with the exact values,
  2. unknown hosts get the proxy's 404 on every path,
  3. the attacker page is gone (the backend page is what gets served).

Exit code 0 means every check passed. This test never prints accepted answers.
"""
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
PORT = 28852

BACKEND_MARKER = "ExampleAPI"

# The exact security headers and values the backend sets on every response.
REQUIRED_HEADERS = {'X-Content-Type-Options': 'nosniff', 'X-Frame-Options': 'DENY', 'Strict-Transport-Security': 'max-age=31536000; includeSubDomains'}

PROXIED_PATHS = ["/", "/status", "/api/v1/health"]
DIRECT_PATHS = ["/internal", "/internal/status", "/internal/api/v1/health"]


def fetch(host, path):
    req = urllib.request.Request("http://127.0.0.1:%d%s" % (PORT, path),
                                 headers={"Host": host})
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, dict(resp.headers), resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        return exc.code, dict(exc.headers), exc.read().decode("utf-8", "replace")


def main():
    failures = []
    proc = subprocess.Popen([sys.executable, os.path.join(ROOT, "server/app.py"),
                             str(PORT)],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        ready = False
        for _ in range(40):
            try:
                status, headers, body = fetch("api.example.test", "/internal")
                ready = True
                break
            except Exception:
                time.sleep(0.25)
        if not ready:
            print("FAIL")
            print(" - the gateway did not come up on 127.0.0.1:%d" % PORT)
            return 1

        # 1. the hijacked host must reach the backend with all security headers,
        #    on the proxied public routes and on the direct /internal pages.
        for path in PROXIED_PATHS + DIRECT_PATHS:
            status, headers, body = fetch("api.example.test", path)
            if status != 200:
                failures.append("api.example.test%s does not get 200 from the backend (got %d)"
                                % (path, status))
                continue
            if BACKEND_MARKER not in body:
                failures.append("api.example.test%s does not serve the backend page" % path)
            lowered = {k.lower(): v for k, v in headers.items()}
            for name, value in REQUIRED_HEADERS.items():
                got = lowered.get(name.lower())
                if got is None:
                    failures.append("api.example.test%s response is missing %s" % (path, name))
                elif got.strip() != value:
                    failures.append("%s has a loosened value on api.example.test%s" % (name, path))

        # 2. unknown hosts still get the proxy 404, on every path.
        for path in ["/", "/internal", "/status"]:
            status, headers, body = fetch("no-such-host.example.test", path)
            if status != 404:
                failures.append("unknown host does not get the proxy 404 on %s (got %d)"
                                % (path, status))

        # 3. the attacker page is gone: the home page is the backend's, not the
        #    "maintenance portal" (it does not carry the backend marker).
        status, headers, body = fetch("api.example.test", "/")
        if status == 200 and BACKEND_MARKER not in body:
            failures.append("the attacker page is still served on api.example.test")

        if failures:
            print("FAIL")
            for f in failures:
                print(" - " + f)
            return 1
        print("PASS: api.example.test reaches the backend with all security headers, "
              "unknown hosts 404, attacker page gone")
        return 0
    finally:
        proc.terminate()
        proc.wait()


if __name__ == "__main__":
    sys.exit(main())
