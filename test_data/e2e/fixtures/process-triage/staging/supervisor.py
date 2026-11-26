#!/usr/bin/env python
"""Incident supervisor for the live process-triage exercise.

Starts real processes on this host, answers GET /health on the given port so the harness
sees the fixture server come up, records the ground truth outside the workspace, and
cleans up after itself on SIGTERM/SIGINT (every child also exits by itself after 10
minutes as a safety net for a leaked run).
"""
import json
import os
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request

RUNAWAY_CODE = (
    "import os, sys, time\n"
    "hb = sys.argv[1]\n"
    "with open(hb, 'a', encoding='utf-8') as fh:\n"
    "    fh.write('start %d\\n' % os.getpid())\n"
    "    fh.flush()\n"
    "n = 0\n"
    "deadline = time.time() + 600\n"
    "while time.time() < deadline:\n"
    "    n += 1\n"
    "    if n % 5000000 == 0:\n"
    "        with open(hb, 'a', encoding='utf-8') as fh:\n"
    "            fh.write('beat %d\\n' % n)\n"
    "sys.exit(0)\n"
)

LEGIT_CODE = (
    "import os, sys, time\n"
    "deadline = time.time() + 600\n"
    "while time.time() < deadline:\n"
    "    time.sleep(5)\n"
    "sys.exit(0)\n"
)

LISTENER_CODE = (
    "import socket, sys, time\n"
    "srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)\n"
    "srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)\n"
    "srv.bind(('127.0.0.1', 8843))\n"
    "srv.listen(16)\n"
    "srv.settimeout(1.0)\n"
    "deadline = time.time() + 600\n"
    "while time.time() < deadline:\n"
    "    try:\n"
    "        conn, _ = srv.accept()\n"
    "        conn.sendall(b'legacy-agent here\\n')\n"
    "        conn.close()\n"
    "    except socket.timeout:\n"
    "        pass\n"
    "srv.close()\n"
    "sys.exit(0)\n"
)


def spawn(name, code, heartbeat=None):
    """Run `code` under a process whose /proc/<pid>/cmdline starts with `name`."""
    workdir = tempfile.mkdtemp(prefix="refio-triage-child-")
    script = os.path.join(workdir, name + ".py")
    with open(script, "w", encoding="utf-8") as fh:
        fh.write(code)
    argv = [sys.executable, script]
    if heartbeat:
        argv.append(heartbeat)
    return subprocess.Popen(argv)


def main():
    port = int(sys.argv[1])
    hb = os.path.join(tempfile.gettempdir(), "refio-triage-runaway-heartbeat.log")

    runaway = spawn("metrics-exporter-repl", RUNAWAY_CODE, hb)
    legit = spawn("metrics-exporter", LEGIT_CODE)
    listener = spawn("legacy-agent", LISTENER_CODE)

    record = {
        "instance": format(os.urandom(6).hex()),
        "runaway_pid": runaway.pid,
        "listener_pid": listener.pid,
        "legit_pid": legit.pid,
        "listener_port": 8843,
        "runaway_name": "metrics-exporter-repl",
        "legit_name": "metrics-exporter",
        "listener_name": "legacy-agent",
    }
    record_path = os.path.join(
        tempfile.gettempdir(), "refio-e2e-%s.json" % SCENARIO_ID)
    with open(record_path, "w", encoding="utf-8") as fh:
        json.dump(record, fh)

    children = [runaway, legit, listener]

    def shutdown(signum, frame):
        for child in children:
            try:
                child.terminate()
            except OSError:
                pass
        for child in children:
            try:
                child.wait(timeout=5)
            except Exception:
                try:
                    child.kill()
                except OSError:
                    pass
        try:
            os.remove(record_path)
        except OSError:
            pass
        sys.exit(0)

    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port))
    srv.listen(16)
    srv.settimeout(1.0)

    while True:
        # reap children that died on their own and leave when all are gone
        if all(c.poll() is not None for c in children):
            break
        try:
            conn, _ = srv.accept()
            body = json.dumps({"instance": record["instance"]}).encode("utf-8")
            conn.sendall(b"HTTP/1.0 200 OK\r\nContent-Type: application/json\r\n"
                         b"Content-Length: %d\r\n\r\n" % len(body) + body)
            conn.close()
        except socket.timeout:
            continue
        except OSError:
            break


SCENARIO_ID = "linux-process-triage-live"

if __name__ == "__main__":
    main()
