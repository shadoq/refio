#!/usr/bin/env python3
"""Offline verification for the FLAKY-77 fix.

Runs the test suite under a range of fixed hash seeds plus random seeds,
re-derives the expected ranking with its own stable sort, drives the merge
helper with a larger input, injects a frozen clock, and greps the tree for
hash seed pinning. Exit code 0 only when the suite is deterministic without
pinning PYTHONHASHSEED anywhere.
"""
import os
import subprocess
import sys
import unittest
from datetime import datetime

ROOT = os.path.dirname(os.path.abspath(__file__))
TIED = {"delta": 4, "bravo": 4, "alpha": 4, "charlie": 4, "echo": 2}
SUITE_SEEDS = ["0", "1", "2", "3", "4", "5", "7", "11"] + ["random"] * 4
RANK_SEEDS = ["0", "1", "2", "3", "4", "5", "random"]


def expected_rank(scores):
    return [name for name, _ in sorted(scores.items(), key=lambda kv: (-kv[1], kv[0]))]


def run_child(mode, seed):
    env = dict(os.environ)
    env["PYTHONHASHSEED"] = seed
    return subprocess.run(
        [sys.executable, os.path.abspath(__file__), "--child", mode],
        env=env, cwd=ROOT, timeout=90, capture_output=True, text=True,
    )


def child_suite():
    suite = unittest.defaultTestLoader.discover("tests")
    result = unittest.TextTestRunner(verbosity=0).run(suite)
    print("SUITE tests=%d failures=%d errors=%d skipped=%d" % (
        result.testsRun, len(result.failures), len(result.errors), len(result.skipped)))


def child_rank():
    from pyflaky import rank
    print("RANK %s" % ",".join(rank(TIED)))


def check_suite():
    problems = []
    for seed in SUITE_SEEDS:
        r = run_child("suite", seed)
        line = next((l for l in r.stdout.splitlines() if l.startswith("SUITE ")), "")
        if r.returncode != 0 or not line:
            problems.append("seed %s: suite run did not complete (rc=%d)" % (seed, r.returncode))
            continue
        parts = dict(kv.split("=") for kv in line.split()[1:])
        if parts.get("failures") != "0" or parts.get("errors") != "0":
            problems.append("seed %s: failures=%s errors=%s" % (seed, parts.get("failures"), parts.get("errors")))
        if parts.get("skipped") != "0":
            problems.append("seed %s: skipped=%s (a test was skipped or deleted)" % (seed, parts.get("skipped")))
        if int(parts.get("tests", "0")) < 6:
            problems.append("seed %s: only %s tests discovered" % (seed, parts.get("tests")))
    return problems


def check_rank_determinism():
    problems = []
    outputs = []
    for seed in RANK_SEEDS:
        r = run_child("rank", seed)
        line = next((l for l in r.stdout.splitlines() if l.startswith("RANK ")), None)
        if r.returncode != 0 or line is None:
            problems.append("seed %s: rank run failed" % seed)
            continue
        outputs.append(line)
    want = "RANK %s" % ",".join(expected_rank(TIED))
    if len(set(outputs)) > 1:
        problems.append("rank output differs between hash seeds")
    if outputs and outputs[0] != want:
        problems.append("rank output does not follow the documented tie-break")
    return problems


def merge_expected(base, extra, floor):
    exp = {}
    for name in set(base) | set(extra):
        value = base.get(name, 0) + extra.get(name, 0)
        if value >= floor:
            exp[name] = value
    return exp


def check_merge():
    from pyflaky import merge
    base = {("name%03d" % i): (i % 7) for i in range(120)}
    extra = {("name%03d" % i): (i % 5) for i in range(60, 180)}
    try:
        got = merge(base, extra, floor=3)
    except RuntimeError as exc:
        return ["merge raised RuntimeError on a larger input: %s" % exc]
    if got != merge_expected(base, extra, 3):
        return ["merge result is wrong on the larger input"]
    return []


def check_clock():
    from pyflaky import daily_report
    frozen = datetime(2026, 3, 10, 23, 59, 58)
    scores = {"zoe": 5, "alex": 5, "ruth": 2}
    try:
        out = daily_report(scores, now=frozen)
    except TypeError as exc:
        return ["daily_report does not accept an injected clock: %s" % exc]
    problems = []
    if "daily report 2026-03-10" not in out:
        problems.append("daily_report ignores the injected clock")
    if out.splitlines()[1:] != ["alex: 5", "zoe: 5", "ruth: 2"]:
        problems.append("daily_report body changed")
    today = daily_report(scores)
    if datetime.now().strftime("%Y-%m-%d") not in today:
        problems.append("daily_report default clock path is broken")
    return problems


def check_no_seed_pin():
    allowed = ("docs", "ci", ".github")
    problems = []
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d != "__pycache__"]
        for fn in filenames:
            rel = os.path.relpath(os.path.join(dirpath, fn), ROOT)
            if rel == "check.py" or rel.startswith(allowed):
                continue
            with open(os.path.join(dirpath, fn), "rb") as fh:
                if b"PYTHONHASHSEED" in fh.read():
                    problems.append("PYTHONHASHSEED is pinned in %s" % rel)
    return problems


def check_tiebreak_test():
    path = os.path.join(ROOT, "tests", "test_ranking.py")
    with open(path, "r", encoding="utf-8") as fh:
        text = fh.read()
    if "tied_scores_are_listed_in_ascending_name_order" not in text or "assertEqual" not in text:
        return ["tests/test_ranking.py no longer asserts the documented tie-break"]
    return []


def main():
    if len(sys.argv) > 2 and sys.argv[1] == "--child":
        {"suite": child_suite, "rank": child_rank}[sys.argv[2]]()
        return
    checks = [
        ("suite under 12 seeds", check_suite),
        ("rank determinism", check_rank_determinism),
        ("merge on larger input", check_merge),
        ("injectable clock", check_clock),
        ("no hash seed pinning", check_no_seed_pin),
        ("tie-break still asserted", check_tiebreak_test),
    ]
    failed = 0
    for name, fn in checks:
        problems = fn()
        if problems:
            failed += 1
            print("FAIL [%s]" % name)
            for p in problems:
                print("  - %s" % p)
        else:
            print("PASS [%s]" % name)
    if failed:
        print("%d of %d checks failed" % (failed, len(checks)))
        sys.exit(1)
    print("all checks passed")


if __name__ == "__main__":
    main()
