#!/usr/bin/env python3
"""Acceptance gate: re-derive every number of report/inventory.txt from
etc/passwd, etc/group and stat() of the srv/ tree. Nothing is hard-coded;
the filesystem is the only source of truth. Only the permission bits are
looked at; owners and timestamps are ignored."""
import os
import stat as statmod
import sys

NOLOGIN = "/sbin/nologin"
KEY_RE = None


def fail(msg):
    print("FAIL: " + msg)
    sys.exit(1)


def parse_passwd():
    """Return the list of (name, gid, shell) entries."""
    out = []
    try:
        with open("etc/passwd", encoding="utf-8") as fh:
            raw = fh.read()
    except OSError:
        fail("etc/passwd unreadable")
    for line in raw.splitlines():
        line = line.rstrip("\n")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        parts = line.split(":")
        if len(parts) != 7:
            fail("etc/passwd line does not have 7 fields: %r" % line)
        out.append((parts[0], parts[2], parts[3], parts[6]))
    return out


def parse_group():
    """Return gid -> primary group name."""
    names = {}
    try:
        with open("etc/group", encoding="utf-8") as fh:
            raw = fh.read()
    except OSError:
        fail("etc/group unreadable")
    for line in raw.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        parts = line.split(":")
        if len(parts) != 4:
            fail("etc/group line does not have 4 fields: %r" % line)
        names[parts[2]] = parts[0]
    return names


def rederive():
    entries = parse_passwd()
    groups = parse_group()
    nologin = [e for e in entries if e[3] == NOLOGIN]
    gids = set(e[2] for e in nologin)
    missing = [g for g in gids if g not in groups]
    if missing:
        fail("etc/group lacks primary group(s) %s (scenario drift)" % missing)
    grp = sorted(set(groups[g] for g in gids))
    files_750 = []
    for root, dirs, files in os.walk("srv"):
        for name in files:
            rel = os.path.relpath(os.path.join(root, name))
            st = os.stat(os.path.join(root, name))
            if statmod.S_IMODE(st.st_mode) == 0o750:
                files_750.append(rel)
    files_750.sort()
    return str(len(nologin)), ", ".join(grp), files_750


def main():
    import re
    global KEY_RE
    KEY_RE = re.compile(r"^([a-z0-9_]+) = (.+)$")
    nologin_count, groups_line, files_750 = rederive()
    try:
        with open("report/inventory.txt", encoding="utf-8") as fh:
            raw = fh.read()
    except OSError:
        print("FAIL: report/inventory.txt is missing")
        return 1
    got = {}
    order = []
    for lineno, line in enumerate(raw.splitlines(), 1):
        if not line.strip():
            continue
        m = KEY_RE.match(line)
        if not m:
            print("FAIL: line %d is not a 'key = value' line: %r"
                  % (lineno, line))
            return 1
        key, value = m.group(1), m.group(2)
        if key in got:
            print("FAIL: key %s appears twice" % key)
            return 1
        got[key] = value
        order.append(key)
    expected_keys = {"nologin_count", "groups_with_nologin", "files_group_only"}
    problems = []
    if set(order) != expected_keys:
        problems.append("keys are exactly %s (got %s)"
                        % (sorted(expected_keys), sorted(order)))
    else:
        if got["nologin_count"] != nologin_count:
            problems.append("nologin_count is %r, expected %r"
                            % (got["nologin_count"], nologin_count))
        if got["groups_with_nologin"] != groups_line:
            problems.append("groups_with_nologin is %r, expected %r"
                            % (got["groups_with_nologin"], groups_line))
        if got["files_group_only"] != ", ".join(files_750):
            problems.append("files_group_only is %r, expected %r"
                            % (got["files_group_only"], ", ".join(files_750)))
    if problems:
        print("FAIL: " + "; ".join(problems))
        return 1
    print("PASS: inventory matches passwd/group and the srv/ modes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
