#!/usr/bin/env python3
"""Acceptance gate: re-apply the pin stanzas of etc/apt/preferences.d/pinning
to packages.txt and compare report/allowed.txt and report/denied-count.txt
against the result. No expected value is hard-coded; the pinning file and the
snapshot are the only sources of truth."""
import fnmatch
import re
import sys

PINNING = "etc/apt/preferences.d/pinning"
PACKAGES = "packages.txt"
ALLOWED = "report/allowed.txt"
DENIED = "report/denied-count.txt"
HEADER = "# name=version origin=<origin>"
ROW_RE = re.compile(r"^(\S+)=(\S+) origin=(\S+)$")


def fail(msg):
    print("FAIL: " + msg)
    sys.exit(1)


def parse_stanzas(text):
    stanzas = []
    cur = None
    for raw in text.splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line or line.startswith("Explanation:"):
            continue
        m = re.match(r"^Package:\s*(.+)$", line)
        if m:
            cur = {}
            continue
        m = re.match(r"^Pin:\s*release\s+([oac])=(\S+)$", line)
        if m and cur is not None:
            cur["kind"], cur["value"] = "release-" + m.group(1), m.group(2)
            continue
        m = re.match(r"^Pin:\s*version\s+(\S+)$", line)
        if m and cur is not None:
            cur["kind"], cur["value"] = "version", m.group(1)
            continue
        if re.match(r"^Pin-Priority:\s*-?\d+$", line) and cur is not None:
            if "kind" in cur:
                stanzas.append(cur)
            cur = None
    return stanzas


def stanza_matches(st, name, version, origin, component):
    kind, value = st["kind"], st["value"]
    if kind == "release-o":
        return origin.split("/", 1)[0] == value
    if kind == "release-a":
        parts = origin.split("/", 1)
        return len(parts) == 2 and parts[1] == value
    if kind == "release-c":
        return component == value
    if kind == "version":
        return fnmatch.fnmatchcase(version, value)
    return False


def parse_snapshot(text):
    rows = []
    for lineno, line in enumerate(text.splitlines(), 1):
        if not line.strip():
            continue
        parts = line.split()
        if len(parts) != 4:
            fail("packages.txt line %d is not a 4-column snapshot line" % lineno)
        rows.append((parts[0], parts[1], parts[2], parts[3]))
    if not rows:
        fail("internal check - packages.txt derives no rows (scenario drift)")
    return rows


def main() -> int:
    with open(PINNING, encoding="utf-8") as fh:
        stanzas = parse_stanzas(fh.read())
    if not stanzas:
        fail("internal check - the pinning file derives no stanzas (scenario drift)")
    with open(PACKAGES, encoding="utf-8") as fh:
        rows = parse_snapshot(fh.read())

    allowed = [r for r in rows
               if any(stanza_matches(st, *r) for st in stanzas)]
    expected_rows = sorted(((r[0], r[1], r[2]) for r in allowed),
                           key=lambda t: (t[0], t[1]))
    expected_denied = len(rows) - len(allowed)

    try:
        with open(ALLOWED, encoding="utf-8") as fh:
            raw = fh.read()
    except OSError:
        fail("report/allowed.txt is missing")
    lines = raw.splitlines()
    if not lines or lines[0] != HEADER:
        fail("report/allowed.txt must start with the header line %r" % HEADER)
    got_rows = []
    for i, line in enumerate(lines[1:], 2):
        if not line.strip():
            fail("report/allowed.txt line %d is blank" % i)
        m = ROW_RE.match(line)
        if not m:
            fail("report/allowed.txt line %d is not 'name=version origin=<origin>'" % i)
        got_rows.append((m.group(1), m.group(2), m.group(3)))
    if got_rows != expected_rows:
        fail("report/allowed.txt does not match the pin rules applied to packages.txt")

    try:
        with open(DENIED, encoding="utf-8") as fh:
            dlines = [l for l in fh.read().splitlines() if l.strip()]
    except OSError:
        fail("report/denied-count.txt is missing")
    if len(dlines) != 1 or not re.match(r"^denied_count = \d+$", dlines[0]):
        fail("report/denied-count.txt must be a single 'denied_count = N' line")
    if int(dlines[0].split("=")[1]) != expected_denied:
        fail("denied_count does not match the pin rules applied to packages.txt")

    print("PASS: allowed list and denied count match the pin rules")
    return 0


if __name__ == "__main__":
    sys.exit(main())
