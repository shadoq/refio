#!/usr/bin/env python3
"""Acceptance gate for the procedural-to-OOP refactor.

Drives the public pipeline API end to end - per-record results with their
exact reasons, sequence numbers, per-source attribution, the summary shape,
reset semantics and the CLI contract - so the behaviour is pinned down
before and after the refactor.

This file is part of the scenario definition - editing it is not a fix.

Run: python3 checks.py
"""
import contextlib
import io
import json
import os
import subprocess
import sys

BASE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, BASE)

import pipeline  # noqa: E402

failures = []


def check(name, ok):
    print(("PASS" if ok else "FAIL") + " - " + name)
    if not ok:
        failures.append(name)


# --- 1. per-record results and rejection reasons --------------------------
pipeline.reset()
r1 = pipeline.process_record(
    {"source": "edge-1", "metric": "cpu", "value": 0.73, "ts": 1690000001}
)
check(
    "valid record accepted with seq 1",
    r1 == {"status": "accepted", "reason": None, "seq": 1},
)

r2 = pipeline.process_record(
    {"source": "edge-1", "metric": "mem", "value": 0.42, "ts": 1690000002}
)
check(
    "second record gets seq 2",
    r2 == {"status": "accepted", "reason": None, "seq": 2},
)

r3 = pipeline.process_record(
    {"source": "edge-2", "metric": "cpu", "value": 7.5, "ts": 1690000003}
)
check(
    "out-of-range ratio rejected with the exact reason",
    r3
    == {
        "status": "rejected",
        "reason": "value out of range: cpu must be within 0..1",
        "seq": 3,
    },
)

r4 = pipeline.process_record(
    {"source": "edge-2", "metric": "rps", "value": -1, "ts": 1690000004}
)
check(
    "negative counter metric rejected",
    r4["status"] == "rejected"
    and r4["reason"] == "value out of range: rps must be >= 0",
)

r5 = pipeline.process_record({"metric": "cpu", "value": 0.5, "ts": 1690000005})
check("missing field rejected", r5["reason"] == "missing field: source")

r6 = pipeline.process_record(
    {"source": "edge-3", "metric": "cpu", "value": True, "ts": 1690000006}
)
check("boolean value rejected", r6["reason"] == "bad field: value")

r7 = pipeline.process_record("not even a dict")
check("non-dict record rejected", r7["reason"] == "record must be an object")

# --- 2. the summary reflects the fold --------------------------------------
s = pipeline.summarize()
check(
    "summary counts the fold",
    s["received"] == 7 and s["accepted"] == 2 and s["rejected"] == 5,
)
check(
    "per-source attribution counts received records",
    s["by_source"] == {"edge-1": 2, "edge-2": 2, "edge-3": 1},
)
check("acceptance rate is rounded to 3 places", s["acceptance_rate"] == 0.286)
check("the log holds one entry per record", s["records"] == 7)

# --- 3. replay drives batches ----------------------------------------------
pipeline.reset()
batch = [
    {"source": "edge-9", "metric": "latency", "value": 123, "ts": 1690000007},
    {"source": "edge-9", "metric": "disk", "value": 0.9, "ts": 1690000008},
    {"source": "edge-9", "metric": "mem", "value": 2.0, "ts": 1690000009},
]
out = pipeline.replay(batch)
check(
    "replay returns per-record results in order",
    [r["status"] for r in out] == ["accepted", "accepted", "rejected"]
    and [r["seq"] for r in out] == [1, 2, 3],
)
check(
    "replay folds into the running state",
    pipeline.summarize()["by_source"] == {"edge-9": 3},
)

# --- 4. reset clears the world ----------------------------------------------
pipeline.reset()
check(
    "reset clears every counter",
    pipeline.summarize()
    == {
        "received": 0,
        "accepted": 0,
        "rejected": 0,
        "by_source": {},
        "records": 0,
        "acceptance_rate": 0.0,
    },
)
check(
    "counters restart after reset",
    pipeline.process_record(
        {"source": "edge-1", "metric": "cpu", "value": 0.1, "ts": 1690000010}
    )["seq"]
    == 1,
)

# --- 5. the CLI contract -----------------------------------------------------
buf = io.StringIO()
with contextlib.redirect_stdout(buf):
    code = pipeline.main(
        ['{"source": "edge-4", "metric": "cpu", "value": 0.5, "ts": 1690000011}', "not-json"]
    )
check("main returns 0 for a processed batch", code == 0)
printed = json.loads(buf.getvalue())
check(
    "main prints the batch summary as JSON",
    printed["received"] == 2
    and printed["accepted"] == 1
    and printed["rejected"] == 1
    and printed["by_source"] == {"edge-4": 1},
)

proc = subprocess.run(
    [
        sys.executable,
        "pipeline.py",
        '{"source": "edge-5", "metric": "rps", "value": 42, "ts": 1690000012}',
    ],
    capture_output=True,
    text=True,
    cwd=BASE,
)
check(
    "the CLI subprocess prints the summary and exits 0",
    proc.returncode == 0
    and json.loads(proc.stdout)["by_source"] == {"edge-5": 1},
)

proc = subprocess.run(
    [sys.executable, "pipeline.py"],
    capture_output=True,
    text=True,
    cwd=BASE,
)
check(
    "the CLI subprocess exits 2 with a usage message on no arguments",
    proc.returncode == 2 and "usage" in proc.stderr,
)

print()
if failures:
    print(f"checks: {len(failures)} check(s) FAILED: " + "; ".join(failures))
    sys.exit(1)
print("checks: all checks passed")
