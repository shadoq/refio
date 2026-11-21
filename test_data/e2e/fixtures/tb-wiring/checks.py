#!/usr/bin/env python3
"""Acceptance gate: run.env must implement docs/topology.md exactly, and the wired
chain must actually answer end-to-end (the gate boots all three services)."""
import os
import subprocess
import sys
import time
import urllib.request

EXPECTED = {
    "INGRESS_PORT": "8771",
    "INGRESS_UPSTREAM": "http://127.0.0.1:8772",
    "LOGIC_PORT": "8772",
    "LOGIC_UPSTREAM": "http://127.0.0.1:8773",
    "STORE_PORT": "8773",
}

ROOT = os.path.dirname(os.path.abspath(__file__))


def parse_run_env():
    env = {}
    with open(os.path.join(ROOT, "run.env"), encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            env[key.strip()] = value.strip()
    return env


def main() -> int:
    problems = []
    env = parse_run_env()
    for key, want in EXPECTED.items():
        got = env.get(key)
        if got != want:
            problems.append(f"{key}={got!r}, topology requires {want!r}")
    extra = sorted(set(env) - set(EXPECTED))
    if extra:
        problems.append(f"unexpected keys in run.env: {extra}")
    if problems:
        print("FAIL: " + "; ".join(problems))
        return 1

    boot_env = {**os.environ, **env}
    procs = []
    try:
        for service in ("ingress.py", "logic.py", "store.py"):
            procs.append(
                subprocess.Popen(
                    [sys.executable, os.path.join(ROOT, "services", service)],
                    env=boot_env,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.PIPE,
                )
            )
        deadline = time.time() + 15
        ready = False
        while time.time() < deadline and not ready:
            if any(p.poll() is not None for p in procs):
                died = [p for p in procs if p.poll() is not None][0]
                stderr = died.stderr.read().decode(errors="replace") if died.stderr else ""
                print(f"FAIL: a service exited early (code {died.returncode}): {stderr[:200]}")
                return 1
            try:
                with urllib.request.urlopen("http://127.0.0.1:8771/health", timeout=2) as resp:
                    if resp.status == 200:
                        ready = True
            except Exception:
                time.sleep(0.3)
        if not ready:
            print("FAIL: the chain never became ready")
            return 1
        with urllib.request.urlopen("http://127.0.0.1:8771/fetch?value=2", timeout=5) as resp:
            answer = resp.read().decode().strip()
        if answer != "stored: 21":
            print(f"FAIL: end-to-end answer was {answer!r}, expected 'stored: 21' "
                  f"(2 +1 -> 3, *7 -> 21 through the wired chain)")
            return 1
        print("PASS: run.env matches the topology and the chain answers end-to-end")
        return 0
    finally:
        for proc in procs:
            proc.terminate()
        for proc in procs:
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()


if __name__ == "__main__":
    sys.exit(main())
