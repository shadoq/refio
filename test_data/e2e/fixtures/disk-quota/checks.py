#!/usr/bin/env python3
"""Acceptance gate: report/cleanup-plan.txt must match the retention policy exactly.

The plan is the operation manifest the operator will execute - every path on it is
recomputed here from the filenames and the policy reference date (2026-06-30), so the
manifest cannot be guessed; it has to be derived.
"""
import re
import sys
from datetime import date, timedelta

REF_DATE = date(2026, 6, 30)
LOG_KEEP = 10
TMP_MAX_AGE_DAYS = 7

LOG_RE = re.compile(r"^data/logs/app-(\d{4}-\d{2}-\d{2})\.log$")
TMP_RE = re.compile(r"^data/tmp/cache-(\d{4}-\d{2}-\d{2})-\d+\.tmp$")


def expected_plan(files):
    logs = []
    tmps = []
    for name in files:
        m = LOG_RE.match(name)
        if m:
            logs.append((date.fromisoformat(m.group(1)), name))
            continue
        m = TMP_RE.match(name)
        if m:
            tmps.append((date.fromisoformat(m.group(1)), name))
    drop = set()
    logs.sort(reverse=True)
    for _, name in logs[LOG_KEEP:]:
        drop.add(name)
    cutoff = REF_DATE - timedelta(days=TMP_MAX_AGE_DAYS)
    for d, name in tmps:
        if d < cutoff:
            drop.add(name)
    return sorted(drop)


def main() -> int:
    import os

    files = []
    for dirpath, _, names in os.walk("data"):
        for n in names:
            files.append(os.path.join(dirpath, n))
    expected = expected_plan(sorted(files))
    if not expected:
        print("FAIL: internal check - policy derives an empty plan (scenario drift)")
        return 1
    try:
        with open("report/cleanup-plan.txt", encoding="utf-8") as fh:
            lines = [ln.strip() for ln in fh.read().splitlines() if ln.strip()]
    except OSError:
        print("FAIL: report/cleanup-plan.txt is missing")
        return 1
    if lines != expected:
        missing = [p for p in expected if p not in lines]
        extra = [p for p in lines if p not in expected]
        print("FAIL: plan does not match the policy recomputation:")
        if missing:
            print(f"     missing: {missing[:6]}{'...' if len(missing) > 6 else ''}")
        if extra:
            print(f"     extra:   {extra[:6]}{'...' if len(extra) > 6 else ''}")
        print("     (order must be sorted, paths relative, no comments)")
        return 1
    kept = [name for name in files
            if name.startswith("data/reports/") or name not in set(expected)]
    gone = [name for name in kept if not os.path.isfile(name)]
    if gone:
        print(f"FAIL: files that must be kept are missing: {gone[:5]}")
        return 1
    print("PASS: cleanup plan matches the policy (order, paths, no extras)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
