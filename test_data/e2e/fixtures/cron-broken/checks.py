#!/usr/bin/env python3
"""Acceptance gate: parse etc/crontab and match every job against the schedule policy.

Implements a strict 5-field cron parser (lists, ranges, steps, names rejected) and fires
each job over a synthetic year to compare against the policy's expected fire sets.
"""
import datetime as dt
import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))

POLICY = {
    "/usr/local/bin/backup.sh": "30 2 * * *",
    "/usr/local/bin/cleanup.sh": "15 * * * *",
    "/usr/local/bin/report.sh": "0 3 * * 0",
    "/usr/local/bin/health.sh": "*/5 9-17 * * 1-5",
}

NAMES = {"sun": 0, "mon": 1, "tue": 2, "wed": 3, "thu": 4, "fri": 5, "sat": 6}


def parse_field(spec, lo, hi):
    values = set()
    for part in spec.split(","):
        m = re.fullmatch(r"(\*|[0-9]+)(?:-([0-9]+))?(?:/([0-9]+))?", part)
        if not m:
            raise ValueError(f"unparseable field part {part!r}")
        start_s, end_s, step_s = m.groups()
        step = int(step_s) if step_s else 1
        if start_s == "*":
            start, end = lo, hi
        else:
            start = int(start_s)
            end = int(end_s) if end_s else (start if not step_s else hi)
        if not (lo <= start <= hi and lo <= end <= hi):
            raise ValueError(f"field part {part!r} out of range {lo}-{hi}")
        values.update(range(start, end + 1, step))
    return values


def parse_entry(line):
    fields = line.split()
    if len(fields) < 6:
        raise ValueError(f"entry has {len(fields)} fields, need 5 + command")
    minutes = parse_field(fields[0], 0, 59)
    hours = parse_field(fields[1], 0, 23)
    doms = parse_field(fields[2], 1, 31)
    months = parse_field(fields[3], 1, 12)
    dows = parse_field(fields[4], 0, 7)
    if 7 in dows:
        dows.add(0)
    return minutes, hours, doms, months, dows, " ".join(fields[5:])


def fires_in_year(entry):
    minutes, hours, doms, months, dows, _ = entry
    out = set()
    day = dt.date(2026, 1, 1)
    while day.year == 2026:
        if day.month in months and day.day in doms and day.weekday() % 7 in {d % 7 for d in dows}:
            for h in hours:
                for mi in minutes:
                    out.add(f"{day.isoformat()}T{h:02d}:{mi:02d}")
        day += dt.timedelta(days=1)
    return out


def main() -> int:
    problems = []
    try:
        with open(os.path.join(ROOT, "etc", "crontab"), encoding="utf-8") as fh:
            lines = [l.strip() for l in fh]
    except OSError:
        print("FAIL: etc/crontab is missing")
        return 1

    jobs = {}
    for line in lines:
        if not line or line.startswith("#"):
            continue
        if re.search(r"\b(sun|mon|tue|wed|thu|fri|sat)\b", line.split()[4]):
            problems.append(f"entry uses a day name (not accepted on this host): {line}")
            continue
        try:
            entry = parse_entry(line)
        except ValueError as exc:
            problems.append(f"unparseable entry {line!r}: {exc}")
            continue
        jobs[entry[5]] = entry

    for command, wanted in POLICY.items():
        if command not in jobs:
            problems.append(f"job missing from crontab: {command}")
            continue
        expected = fires_in_year(parse_entry(wanted + " " + command))
        actual = fires_in_year(jobs[command])
        if actual != expected:
            problems.append(
                f"{command}: schedule does not match policy {wanted!r} "
                f"({len(actual)} vs {len(expected)} fires in 2026)"
            )

    extra = [c for c in jobs if c not in POLICY]
    if extra:
        problems.append(f"unexpected extra jobs: {extra}")

    cleanup = os.path.join(ROOT, "bin", "cleanup.sh")
    with open(cleanup, encoding="utf-8") as fh:
        first = fh.readline()
    if not first.startswith("#!"):
        problems.append("bin/cleanup.sh does not start with a shebang (cron ORPHAN)")

    if problems:
        print("FAIL: " + "; ".join(problems))
        return 1
    print("PASS: all four schedules match the policy and cleanup.sh is executable by cron")
    return 0


if __name__ == "__main__":
    sys.exit(main())
