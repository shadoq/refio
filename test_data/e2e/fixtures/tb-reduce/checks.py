#!/usr/bin/env python3
"""Acceptance gate: report/errors.json must be the per-minute ERROR counts of
logs/service.log - recomputed here from the log itself."""
import json
import re
import sys

LINE_RE = re.compile(r"^2026-06-30T(\d\d:\d\d):\d\d ERROR ")


def recompute():
    counts = {}
    with open("logs/service.log", encoding="utf-8") as fh:
        for line in fh:
            m = LINE_RE.match(line)
            if m:
                key = f"2026-06-30T{m.group(1)}"
                counts[key] = counts.get(key, 0) + 1
    return {k: counts[k] for k in sorted(counts)}


def main() -> int:
    expected = recompute()
    if not expected:
        print("FAIL: internal check - log derives no errors (scenario drift)")
        return 1
    try:
        with open("report/errors.json", encoding="utf-8") as fh:
            reported = json.load(fh)
    except OSError:
        print("FAIL: report/errors.json is missing")
        return 1
    except ValueError as exc:
        print(f"FAIL: report/errors.json does not parse: {exc}")
        return 1
    if not isinstance(reported, dict):
        print("FAIL: report/errors.json must be a JSON object")
        return 1
    missing = {k: v for k, v in expected.items() if reported.get(k) != v}
    extra = {k: v for k, v in reported.items() if k not in expected}
    problems = []
    if missing:
        problems.append(f"{len(missing)} minute keys missing or with wrong counts "
                        f"(first: {sorted(missing)[:3]})")
    if extra:
        problems.append(f"{len(extra)} unexpected keys (minutes without ERROR lines? "
                        f"first: {sorted(extra)[:3]})")
    if problems:
        print("FAIL: " + "; ".join(problems))
        return 1
    print(f"PASS: {len(expected)} per-minute error counts match the log")
    return 0


if __name__ == "__main__":
    sys.exit(main())
