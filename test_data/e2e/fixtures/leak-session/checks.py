#!/usr/bin/env python3
"""Acceptance gate: functional correctness plus tracemalloc bounds on both leak paths.

1. Functional: open/get/close behave; `issued` counts every session ever opened.
2. Churn bound: 20k open/close cycles with ~5 KB payloads; traced peak stays < 40 MB
   (the audit-trail leak pushes this past 90 MB).
3. Live-set bound: 10k opens without close; traced peak stays < 40 MB
   (the unbounded live map pushes this past 45 MB; the 2048-cap stays near 10 MB).
"""
import os
import sys
import tracemalloc

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from src.sessions import SessionStore  # noqa: E402

PAYLOAD_TEMPLATE = bytearray(b"x" * 5 * 1024)  # ~5 KB, the production average


def fresh_payload() -> bytes:
    """A distinct ~5 KB payload per session, like real traffic (no shared object)."""
    return bytes(PAYLOAD_TEMPLATE)


def functional() -> list:
    problems = []
    store = SessionStore()
    store.open("s1", fresh_payload())
    store.open("s2", fresh_payload())
    payload1 = store.get("s1")
    if not (isinstance(payload1, bytes) and len(payload1) == 5 * 1024):
        problems.append("get() does not return a live payload")
    store.close("s1")
    if store.get("s1") is not None:
        problems.append("close() does not remove the session")
    if store.issued != 2:
        problems.append(f"issued is {store.issued}, expected 2 after two opens")
    return problems


def churn() -> list:
    tracemalloc.start()
    store = SessionStore()
    for i in range(20_000):
        sid = f"sid-{i}"
        store.open(sid, fresh_payload())
        store.close(sid)
    _, peak = tracemalloc.get_traced_memory()
    tracemalloc.stop()
    if peak > 40 * 1024 * 1024:
        return [f"churn peak {peak / 1024 / 1024:.1f} MB > 40 MB (audit trail still retains payloads?)"]
    if store.issued != 20_000:
        return [f"issued is {store.issued}, expected 20000"]
    return []


def live_set() -> list:
    tracemalloc.start()
    store = SessionStore()
    for i in range(10_000):
        store.open(f"sid-{i}", fresh_payload())
    _, peak = tracemalloc.get_traced_memory()
    tracemalloc.stop()
    if peak > 40 * 1024 * 1024:
        return [f"unbounded-open peak {peak / 1024 / 1024:.1f} MB > 40 MB (live map unbounded?)"]
    if store.get("sid-0") is None and store.get("sid-9999") is None:
        return ["neither the oldest nor the newest session is retrievable"]
    return []


def main() -> int:
    problems = functional() + churn() + live_set()
    if problems:
        print("FAIL: " + "; ".join(problems))
        return 1
    print("PASS: functional, issued exact, churn and live-set peaks under threshold")
    return 0


if __name__ == "__main__":
    sys.exit(main())
