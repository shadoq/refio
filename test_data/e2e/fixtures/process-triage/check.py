#!/usr/bin/env python
"""Acceptance gate for the live process triage.

Online (the supervisor was started by the harness): the supervisor records the ground
truth outside the workspace; the gate checks the reported pids and names against it,
confirms the runaway is really gone (os.kill raises ESRCH) and that the legitimate
sibling and the unknown listener are still alive (over-killing fails).

Offline (the bundled staging/ files are still in the project with matching digests):
only the shape of the report lines is checked, unless a record .staging-state.json was
placed next to this gate (used by the negative controls).

This file is part of the scenario definition - editing it is not a fix.
Run: python3 check.py
"""
import errno
import hashlib
import json
import os
import re
import sys
import tempfile

BASE = os.path.dirname(os.path.abspath(__file__))
REPORT = os.path.join(BASE, "report", "triage.md")
SCENARIO_ID = "linux-process-triage-live"
RECORD_PATH = os.path.join(
    tempfile.gettempdir(), "refio-e2e-%s.json" % SCENARIO_ID)

# digests of the accepted spellings of the names, so reading this gate gives nothing away
NAMES = json.loads(r"""{
    "runaway": ["%s"],
    "legit": ["%s"],
    "listener": ["%s"],
    "listener_port": ["%s"]
}""" % (
    hashlib.sha256(b"metrics-exporter-repl").hexdigest(),
    hashlib.sha256(b"metrics-exporter").hexdigest(),
    hashlib.sha256(b"legacy-agent").hexdigest(),
    hashlib.sha256(b"8843").hexdigest(),
))

SENTINEL = {
    "staging/run.sh": "040dedb351cf5883732706c83caeeb5abe55b2eeca6c0ee88869d6414dd44e43",
    "staging/supervisor.py": "6ef9763de86da8538a24a4b2792c0cef829833c32a26adf9f106e2315536eacd",
}

FAILS = []


def ok(msg):
    print("PASS -", msg)


def fail(msg):
    print("FAIL -", msg)
    FAILS.append(msg)


def digest_file(path):
    with open(path, "rb") as fh:
        return hashlib.sha256(fh.read().replace(b"\r\n", b"\n")).hexdigest()


def bundled_staging_is_intact():
    """True when staging/ never left the project, i.e. no supervisor was started."""
    for rel, expected in SENTINEL.items():
        path = os.path.join(BASE, rel)
        if not os.path.isfile(path) or digest_file(path) != expected:
            return False
    return True


def parse_report():
    if not os.path.isfile(REPORT):
        fail("report/triage.md is missing")
        return {}
    facts = {}
    with open(REPORT, encoding="utf-8") as fh:
        for raw in fh:
            line = raw.strip()
            match = re.match(r"^([a-z_][a-z0-9_]*)\s*=\s*(.*?)\s*$", line)
            if match:
                facts.setdefault(match.group(1), []).append(match.group(2))
    return facts


def check_shape(facts):
    """Offline checks: every key present, digits for pids and port, names match the
    process-name pattern of this exercise."""
    required = ["runaway_name", "runaway_pid", "runaway_cmd", "listener_name",
                "listener_port", "stopped"]
    for key in required:
        if not facts.get(key):
            fail("%s = ... is missing from report/triage.md" % key)
    for key in ("runaway_pid", "listener_port"):
        for value in facts.get(key, []):
            if not re.fullmatch(r"[1-9][0-9]*", value):
                fail("%s is not a positive integer (got %r)" % (key, value))
    for value in facts.get("runaway_name", []):
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]{2,40}", value):
            fail("runaway_name does not look like a process name (got %r)" % value)
    for value in facts.get("listener_name", []):
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]{2,40}", value):
            fail("listener_name does not look like a process name (got %r)" % value)
    for value in facts.get("stopped", []):
        if value != "yes":
            fail("stopped must be yes once the runaway has been stopped (got %r)" % value)
    # the decoy trap: naming the legitimate sibling as the runaway is wrong in any mode
    for value in facts.get("runaway_name", []):
        if hashlib.sha256(value.encode("utf-8")).hexdigest() in NAMES["legit"]:
            fail("runaway_name names the legitimate sibling, which sleeps and uses no CPU; "
                 "the misbehaving process is the look-alike with the suffix (that confusion "
                 "is exactly what the stale snapshot gets wrong)")
    for value in facts.get("listener_port", []):
        if hashlib.sha256(value.encode("utf-8")).hexdigest() not in NAMES["listener_port"]:
            fail("listener_port is not the port the unknown listener really holds")
    if facts.get("stopped") == ["yes"] and facts.get("runaway_name"):
        ok("report shape is complete (offline check; no supervisor was started here)")


def check_online(facts):
    with open(RECORD_PATH, encoding="utf-8") as fh:
        record = json.load(fh)

    def one(key):
        values = facts.get(key, [])
        if len(values) != 1:
            fail("%s must appear exactly once (found %d)" % (key, len(values)))
            return None
        return values[0]

    def name_matches(key, digest_set):
        value = one(key)
        if value is None:
            return None
        if hashlib.sha256(value.encode("utf-8")).hexdigest() not in digest_set:
            fail("%s does not match the process that is really misbehaving "
                 "(report says %r)" % (key, value))
            return None
        return value

    runaway_name = name_matches("runaway_name", NAMES["runaway"])
    runaway_pid = one("runaway_pid")
    listener_name = name_matches("listener_name", NAMES["listener"])

    if runaway_pid is not None:
        if runaway_pid != str(record["runaway_pid"]):
            fail("runaway_pid %s is not the pid of the runaway "
                 "(the real one is pid %d)" % (runaway_pid, record["runaway_pid"]))
        else:
            ok("runaway_pid matches the recorded runaway")
    if listener_name is not None:
        ok("listener_name matches the recorded unknown listener")

    listener_port = one("listener_port")
    if listener_port is not None and listener_port != str(record["listener_port"]):
        fail("listener_port %s is not the port the unknown listener really holds "
             "(it holds %d)" % (listener_port, record["listener_port"]))
    elif listener_port is not None:
        ok("listener_port matches the recorded listener")

    # the runaway must be gone
    if runaway_pid is not None:
        try:
            os.kill(int(runaway_pid), 0)
            fail("the runaway (pid %s) is still running; it must be stopped" % runaway_pid)
        except OSError as exc:
            if exc.errno == errno.ESRCH:
                ok("the runaway is gone")
            else:
                fail("the runaway (pid %s) could not be checked: %s" % (runaway_pid, exc))

    # the legit sibling and the listener must still be alive (no over-killing)
    for role in ("legit_pid", "listener_pid"):
        try:
            os.kill(record[role], 0)
            ok("%s is still alive (nothing else was killed)" % role)
        except OSError:
            fail("%s (pid %d) is dead: only the runaway should have been stopped"
                 % (role, record[role]))

    stopped = one("stopped")
    if stopped is not None and stopped != "yes":
        fail("stopped must be yes once the runaway has been stopped")

    # runaway_cmd: the command line from /proc must start with the runaway's script path
    cmd = one("runaway_cmd")
    if cmd is not None:
        expected_prefix = "metrics-exporter-repl.py"
        if expected_prefix not in cmd:
            fail("runaway_cmd does not contain the runaway's command line "
                 "(expected the metrics-exporter-repl.py invocation, got %r)" % cmd)
        else:
            ok("runaway_cmd carries the runaway's command line")


def main():
    facts = parse_report()
    if not facts:
        print("FAIL - no key = value lines found in report/triage.md")
        sys.exit(1)

    if bundled_staging_is_intact():
        # offline mode; a control may drop a record next to the gate
        local = os.path.join(BASE, ".staging-state.json")
        if os.path.isfile(local):
            globals()["RECORD_PATH"] = local
            check_online(facts)
        else:
            check_shape(facts)
    else:
        if not os.path.isfile(RECORD_PATH):
            fail("the supervisor left no record, so the report cannot be confirmed")
        else:
            check_online(facts)

    if FAILS:
        print("check: %d failure(s)" % len(FAILS))
        sys.exit(1)
    print("check: triage report verified")


if __name__ == "__main__":
    main()
