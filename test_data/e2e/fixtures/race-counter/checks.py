#!/usr/bin/env python3
"""Acceptance gate: exact-total stress, preserved derivation latency, visible suite green.

1. Stress: 8 threads x 200 increments started together; the total must be exactly 1600
   (lost updates fail).
2. Latency: single-threaded increments must still average >= 0.9 ms (removing the
   compliance window fails).
3. The visible unit suite passes.
"""
import importlib.util
import os
import statistics
import sys
import threading
import time
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from src.counter import RequestCounter  # noqa: E402


def stress(counter, threads=8, per_thread=200):
    barrier = threading.Barrier(threads)

    def worker():
        barrier.wait()
        for _ in range(per_thread):
            counter.increment()

    ts = [threading.Thread(target=worker) for _ in range(threads)]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    return counter.total()


def main() -> int:
    failures = []

    total = stress(RequestCounter())
    if total != 1600:
        failures.append(f"stress total {total} != 1600 (lost updates)")

    probe = RequestCounter()
    samples = []
    for _ in range(40):
        t0 = time.perf_counter()
        probe.increment()
        samples.append(time.perf_counter() - t0)
    avg_ms = statistics.mean(samples) * 1000
    if avg_ms < 0.9:
        failures.append(f"average increment {avg_ms:.3f} ms < 0.9 ms (derivation window removed?)")

    spec = importlib.util.spec_from_file_location(
        "test_counter", os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                     "test", "test_counter.py")
    )
    test_mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(test_mod)
    suite = unittest.defaultTestLoader.loadTestsFromModule(test_mod)
    result = unittest.TextTestRunner(verbosity=0).run(suite)
    if not result.wasSuccessful():
        failures.append("visible unit suite failed")

    if failures:
        print("FAIL: " + "; ".join(failures))
        return 1
    print(f"PASS: stress total exact, derivation window kept ({avg_ms:.2f} ms avg), suite green")
    return 0


if __name__ == "__main__":
    sys.exit(main())
