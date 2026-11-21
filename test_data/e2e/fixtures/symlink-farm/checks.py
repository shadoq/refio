#!/usr/bin/env python3
"""Acceptance gate: releases/links.txt must match docs/release-matrix.md exactly,
and every pointed-to version directory must exist in releases/versions/."""
import re
import sys

MATRIX = {
    "prod": "v2.6.1",
    "canary": "v2.7.0-rc3",
    "rollback": "v2.6.0",
    "demo": "v2.7.0-rc3",
    "perf": "v2.6.1",
    "current": "v2.6.1",
}


def main() -> int:
    try:
        with open("releases/links.txt", encoding="utf-8") as fh:
            lines = [ln.strip() for ln in fh.read().splitlines() if ln.strip() and not ln.strip().startswith("#")]
    except OSError:
        print("FAIL: releases/links.txt is missing")
        return 1
    seen = {}
    for ln in lines:
        m = re.match(r"^([a-z]+)\s*->\s*(v\S+)$", ln)
        if not m:
            print(f"FAIL: malformed line: {ln!r} (expected 'slot -> version')")
            return 1
        slot, version = m.group(1), m.group(2)
        if slot in seen:
            print(f"FAIL: duplicate slot {slot}")
            return 1
        seen[slot] = version
    problems = []
    for slot, want in MATRIX.items():
        got = seen.get(slot)
        if got != want:
            problems.append(f"slot {slot}={got!r}, matrix requires {want!r}")
    for slot in seen:
        if slot not in MATRIX:
            problems.append(f"unknown slot {slot!r} (matrix defines {len(MATRIX)})")
    import os

    for slot, version in seen.items():
        if not os.path.isdir(os.path.join("releases", "versions", version)):
            problems.append(f"slot {slot} points at {version}, which has no version dir")
    if problems:
        print("FAIL: " + "; ".join(problems))
        return 1
    print("PASS: link farm matches the release matrix and all targets exist")
    return 0


if __name__ == "__main__":
    sys.exit(main())
