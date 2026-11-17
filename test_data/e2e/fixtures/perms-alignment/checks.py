#!/usr/bin/env python3
"""Acceptance gate: etc/perms.csv must implement docs/perms-policy.md exactly."""
import csv
import re
import sys

POLICY = {
    "etc/secrets.env": ("0640", "root", "app"),
    "etc/app.conf": ("0644", "root", "root"),
    "var/lib/app/cache": ("2770", "app", "app"),
    "opt/app/bin/run.sh": ("0750", "root", "app"),
    "var/log/app/app.log": ("0640", "app", "app"),
}


def main() -> int:
    try:
        with open("etc/perms.csv", encoding="utf-8", newline="") as fh:
            rows = [r for r in csv.reader(fh) if r]
    except OSError:
        print("FAIL: etc/perms.csv is missing")
        return 1
    if not rows or [c.strip() for c in rows[0]] != ["path", "mode", "owner", "group"]:
        print("FAIL: missing or wrong header (path,mode,owner,group)")
        return 1
    seen = {}
    problems = []
    for row in rows[1:]:
        if len(row) != 4:
            problems.append(f"malformed row: {row!r}")
            continue
        path, mode, owner, group = (c.strip() for c in row)
        if path in seen:
            problems.append(f"duplicate path {path}")
            continue
        seen[path] = (mode, owner, group)
        if not re.match(r"^[0-7]{4}$", mode):
            problems.append(f"{path}: mode {mode!r} is not an octal 0NNN mode")
    for path, want in POLICY.items():
        got = seen.get(path)
        if got is None:
            problems.append(f"missing row for {path}")
            continue
        if got != want:
            problems.append(f"{path}: got {got[0]} {got[1]}:{got[2]}, policy requires "
                            f"{want[0]} {want[1]}:{want[2]}")
    for path in seen:
        if path not in POLICY:
            problems.append(f"row for unknown path {path} (policy covers {len(POLICY)})")
    for path, (mode, _, _) in seen.items():
        if mode[3] in "2367":
            problems.append(f"{path}: mode {mode} is world-writable (policy forbids it)")
    if problems:
        print("FAIL: " + "; ".join(problems))
        return 1
    print("PASS: permission manifest implements the policy exactly")
    return 0


if __name__ == "__main__":
    sys.exit(main())
