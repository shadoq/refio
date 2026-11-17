#!/usr/bin/env python3
"""Acceptance gate for the crash-fix task.

Replays the two failure modes from crash-report.txt plus the visible unit suite. Exit 0 only
when every check passes. This file is part of the scenario definition - editing it is not a
fix.

Run: python3 checks.py
"""
import os
import subprocess
import sys

BASE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(BASE, "src"))

from events import record_event, take_recent  # noqa: E402

failures = []


def check(name, ok):
    print(("PASS" if ok else "FAIL"), "-", name)
    if not ok:
        failures.append(name)


# --- failure mode 1: the digest crash (asked for 5, only 3 available) ---------
try:
    taken = take_recent(["order.placed", "order.paid", "order.shipped"], 5)
    ok = taken == ["order.placed", "order.paid", "order.shipped"]
except IndexError:
    ok = False
check("take_recent takes what is available instead of crashing", ok)

try:
    ok = take_recent(["a", "b", "c", "d"], 2) == ["c", "d"]
except IndexError:
    ok = False
check("take_recent still takes exactly n when available", ok)

try:
    ok = take_recent([], 3) == []
except IndexError:
    ok = False
check("take_recent on an empty log returns empty", ok)

# --- failure mode 2: state shared across independent record_event calls -------
first = record_event("alpha")
second = record_event("beta")
check(
    "independent record_event calls do not share state",
    first == ["alpha"] and second == ["beta"],
)

# --- the visible unit suite must stay green ------------------------------------
proc = subprocess.run(
    [sys.executable, "-m", "unittest", "discover", "-s", "test"],
    cwd=BASE,
    capture_output=True,
    text=True,
)
check("visible unit tests stay green", proc.returncode == 0)

print()
if failures:
    print(f"checks: {len(failures)} check(s) FAILED: " + "; ".join(failures))
    sys.exit(1)
print("checks: all checks passed")
