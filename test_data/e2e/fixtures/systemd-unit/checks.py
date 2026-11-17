#!/usr/bin/env python3
"""Acceptance gate: parse the unit against the spec, then boot the service and probe it.

1. Parse etc/app.service (INI) and require the spec's field values.
2. Parse etc/app.env, apply it to the environment, start opt/app/server.py on a free
   loopback port, poll /health until 200, then also check /who answers.
"""
import configparser
import os
import socket
import subprocess
import sys
import time
import urllib.request

ROOT = os.path.dirname(os.path.abspath(__file__))


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def parse_env(path):
    env = {}
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            env[key.strip()] = value.strip()
    return env


def main() -> int:
    problems = []

    unit = configparser.ConfigParser(strict=False)
    unit.read(os.path.join(ROOT, "etc", "app.service"))
    service = unit["Service"] if "Service" in unit else {}
    expected = {
        "ExecStart": "/usr/bin/python3 /opt/app/server.py",
        "WorkingDirectory": "/opt/app",
        "User": "app",
        "Restart": "always",
        "EnvironmentFile": "/etc/app.env",
    }
    for key, value in expected.items():
        if service.get(key) != value:
            problems.append(f"unit field {key}={service.get(key)!r}, expected {value!r}")
    if problems:
        print("FAIL: " + "; ".join(problems))
        return 1

    env = {**os.environ, **parse_env(os.path.join(ROOT, "etc", "app.env"))}
    if env.get("TOKEN_FILE") != "/opt/app/token.txt":
        print(f"FAIL: TOKEN_FILE is {env.get('TOKEN_FILE')!r}, expected '/opt/app/token.txt'")
        return 1

    # The unit's paths describe the host layout; in this mirror the service runs from the
    # project dir, so remap the spec path to the mirrored file for the boot test.
    env["TOKEN_FILE"] = os.path.join(ROOT, "opt", "app", "token.txt")

    port = free_port()
    env["APP_PORT"] = str(port)
    proc = subprocess.Popen(
        [sys.executable, os.path.join(ROOT, "opt", "app", "server.py")],
        env=env,
        cwd=os.path.join(ROOT, "opt", "app"),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    try:
        base = f"http://127.0.0.1:{port}"
        deadline = time.time() + 15
        ok = False
        while time.time() < deadline:
            if proc.poll() is not None:
                stderr = proc.stderr.read().decode(errors="replace") if proc.stderr else ""
                print(f"FAIL: service exited early (code {proc.returncode}): {stderr.strip()[:200]}")
                return 1
            try:
                with urllib.request.urlopen(base + "/health", timeout=2) as resp:
                    if resp.status == 200 and resp.read().decode() == "ok":
                        ok = True
                        break
            except Exception:
                time.sleep(0.3)
        if not ok:
            print("FAIL: /health never answered")
            return 1
        with urllib.request.urlopen(base + "/who", timeout=2) as resp:
            body = resp.read().decode()
        if not body.startswith("service token prefix:"):
            print(f"FAIL: /who answered unexpectedly: {body!r}")
            return 1
    finally:
        proc.terminate()
        proc.wait(timeout=10)

    print("PASS: unit matches the spec and the service boots and answers /health")
    return 0


if __name__ == "__main__":
    sys.exit(main())
