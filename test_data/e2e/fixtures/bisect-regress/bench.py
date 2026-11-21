#!/usr/bin/env python3
"""Operation-count benchmark for the records index.

Runs a fixed workload against src/index.py (or $BENCH_INDEX_PATH) and counts the
executed lines inside lookup() via sys.settrace - deterministic, wall-clock independent.
Exit 0 = within budget, exit 1 = degraded (too many operations per lookup).
"""
import importlib.util
import os
import sys

N = 2000    # records in the index
Q = 40      # lookups
MAX_LINE_EVENTS = 5000


def load(path):
    spec = importlib.util.spec_from_file_location("index_under_test", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def count_events(fn, args):
    events = 0

    def tracer(frame, event, arg):
        nonlocal events
        if event == "line" and frame.f_code.co_name == "lookup":
            events += 1
        return tracer

    sys.settrace(tracer)
    try:
        fn(*args)
    finally:
        sys.settrace(None)
    return events


def main():
    path = os.environ.get("BENCH_INDEX_PATH", "src/index.py")
    mod = load(path)
    records = [(f"key-{i:06d}", i) for i in range(N)]
    index = mod.build_index(records)
    total = 0
    for i in range(Q):
        total += count_events(mod.lookup, (index, f"key-{(i * 53) % N:06d}"))
    print(f"line-events in lookup: {total}")
    if total > MAX_LINE_EVENTS:
        print(f"BENCH FAIL: {total} > {MAX_LINE_EVENTS} (lookup degraded)")
        return 1
    print("BENCH OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
