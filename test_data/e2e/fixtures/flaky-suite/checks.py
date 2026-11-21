#!/usr/bin/env python3
"""Acceptance gate: the suite must be deterministic under orderings, seedings and clean state.

Runs the suite in four configurations (normal, reversed, two fixed-seed shuffles), each
with a fixed random seed and a wiped scratch directory. Additionally rejects the cheap
way out: every original test method must still exist and no test may be skipped.
"""
import importlib.util
import os
import random
import re
import shutil
import sys
import unittest

ROOT = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, ROOT)

ORIGINAL_TESTS = [
    "test_happy_hour_discount",
    "test_audit_sample_exact",
    "test_summary_export",
    "test_summary_reload",
    "test_audit_sample_all_or_nothing_boundaries",
]


def load_tests():
    spec = importlib.util.spec_from_file_location(
        "test_pricing", os.path.join(ROOT, "test", "test_pricing.py")
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return unittest.defaultTestLoader.loadTestsFromModule(module)


def arrange(suite, order):
    tests = list(suite)
    if order == "reversed":
        tests = tests[::-1]
    elif order == "shuffle-7":
        random.Random(7).shuffle(tests)
    elif order == "shuffle-13":
        random.Random(13).shuffle(tests)
    out = unittest.TestSuite()
    for t in tests:
        out.addTests([t] if not isinstance(t, unittest.TestSuite) else t)
    return out


def run_config(order, seed):
    shutil.rmtree(os.path.join(ROOT, "scratch"), ignore_errors=True)
    random.seed(seed)
    runner = unittest.TextTestRunner(verbosity=0)
    result = runner.run(arrange(load_tests(), order))
    skipped = result.skipped
    return result.wasSuccessful() and not skipped, len(result.failures) + len(result.errors) + len(skipped)


def static_checks():
    problems = []
    with open(os.path.join(ROOT, "test", "test_pricing.py"), encoding="utf-8") as fh:
        source = fh.read()
    for name in ORIGINAL_TESTS:
        if not re.search(rf"def {name}\(", source):
            problems.append(f"test method {name} was deleted or renamed")
    if re.search(r"@\s*unittest\.skip|\.skipTest\(", source):
        problems.append("the suite contains a skip")
    return problems


def main() -> int:
    problems = static_checks()
    configs = [("normal", 1), ("reversed", 2), ("shuffle-7", 7), ("shuffle-13", 13)]
    for order, seed in configs:
        ok, bad = run_config(order, seed)
        if not ok:
            problems.append(f"configuration {order} (seed {seed}) failed {bad} test(s)")
    if problems:
        print("FAIL: " + "; ".join(problems))
        return 1
    print("PASS: suite deterministic across all four configurations, no skips, all tests present")
    return 0


if __name__ == "__main__":
    sys.exit(main())
