#!/usr/bin/env python
"""Acceptance gate for the credential scatter cleanup.

The original key value never appears here in plain text: it and the accepted
report lines are held as SHA-256 digests (the originals are redacted away by
the solution itself). Checks:

  1. the whole project tree (tars opened member by member) contains zero
     occurrences of the original key value;
  2. every original location now contains the literal REDACTED marker where
     the key used to be (a location must be redacted, not truncated away);
  3. report/creds.txt has exactly one "path: occurrences" line per location
     with the true per-location occurrence counts (digests), plus one key_id
     line carrying the first 8 chars of the original key and nothing more.

This file is part of the scenario definition - editing it is not a fix.
Run: python3 check.py
"""
import hashlib
import io
import os
import re
import sys
import tarfile

BASE = os.path.dirname(os.path.abspath(__file__))
FAILS = []

# every place the key was scattered (project-relative) and the count of
# occurrences the pristine snapshot carried there
LOCATIONS = [
    ("home/dev/.bash_history", 3),
    ("home/dev/.gitconfig", 1),
    ("home/dev/notes.txt", 2),
    ("home/dev/backup.tar", 1),
    ("home/dev/app/settings.ini", 1),
]

# SHA-256 of the original key value (the plain value is never stored here).
ORIGINAL_KEY_DIGESTS = {
    '13170043161d6f665f99d38bd304fdcbc1e165be4e403beccbe080864a2b8b5a',
}


def digest(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def ok(message):
    print("PASS -", message)


def fail(message):
    print("FAIL -", message)
    FAILS.append(message)


def key_aware_variants(blob):
    """Digests of key-shaped substrings of the blob: every token (so a value
    standing alone is caught) and everything matching the ACCT-XXXX... key
    pattern (so a value glued to "API_KEY=" is caught too)."""
    out = set()
    try:
        text = blob.decode("utf-8")
    except UnicodeDecodeError:
        return out
    for token in re.findall(r"[A-Za-z0-9_:/.\-]+", text):
        out.add(digest(token))
    for token in re.findall(r"ACCT-[A-Z0-9]+", text):
        out.add(digest(token))
    return out


def iter_project_files():
    """Every file in the project as (relpath, bytes); report/ excluded."""
    for root, dirs, files in os.walk(BASE):
        dirs.sort()
        for name in sorted(files):
            path = os.path.join(root, name)
            rel = os.path.relpath(path, BASE)
            if rel.startswith("report" + os.sep) or rel == "report":
                continue  # the report is checked separately
            with open(path, "rb") as fh:
                yield rel, fh.read()


def tar_members(blob):
    """(member name, bytes) of every regular member, or None if unreadable."""
    try:
        with tarfile.open(fileobj=io.BytesIO(blob), mode="r:*") as tar:
            return [(m.name, tar.extractfile(m).read())
                    for m in tar.getmembers() if m.isfile()]
    except (tarfile.TarError, OSError):
        return None


def main():
    # ---- 1. the original key value survives nowhere in the tree ----
    leaked = []
    for rel, blob in iter_project_files():
        if ORIGINAL_KEY_DIGESTS & key_aware_variants(blob):
            leaked.append(rel)
        # also look inside every tar archive in the tree, member by member
        if blob[:2] == b"\x1f\x8b" or rel.endswith((".tar", ".tar.gz", ".tgz", ".tar.bz2")):
            members = tar_members(blob)
            if members is None:
                leaked.append(rel + " (unreadable archive)")
            else:
                for name, data in members:
                    if ORIGINAL_KEY_DIGESTS & key_aware_variants(data):
                        leaked.append("%s!%s" % (rel, name))
    if leaked:
        fail("the original key value still appears in: %s" % ", ".join(sorted(set(leaked))))
    else:
        ok("no file in the project contains the original key value")

    # ---- 2. every location was redacted, not truncated away ----
    redaction_ok = True
    for rel, _count in LOCATIONS:
        path = os.path.join(BASE, rel)
        if not os.path.isfile(path):
            fail("%s is missing" % rel)
            redaction_ok = False
            continue
        with open(path, "rb") as fh:
            blob = fh.read()
        if rel.endswith((".tar", ".tar.gz", ".tgz", ".tar.bz2")):
            members = tar_members(blob)
            if members is None:
                fail("%s is not a readable tar archive" % rel)
                redaction_ok = False
                continue
        else:
            members = [(rel, blob)]
        for what, data in members:
            if b"REDACTED" not in data:
                fail("%s does not contain the REDACTED marker (a location must "
                     "be redacted, not truncated)" % what)
                redaction_ok = False
    if redaction_ok:
        ok("all five locations carry the REDACTED marker")

    # ---- 3. the report ----
    rpath = os.path.join(BASE, "report", "creds.txt")
    if not os.path.isfile(rpath):
        print("FAIL - report/creds.txt is missing")
        sys.exit(1)
    lines = [l.strip() for l in open(rpath, encoding="utf-8").read().splitlines() if l.strip()]

    key_id_lines = [l for l in lines if l.startswith("key_id")]
    loc_lines = [l for l in lines if l not in key_id_lines]

    # 3a. one "path: occurrences" line per location, true counts (digests)
    expected = {digest("%s: %d" % (rel, count)) for rel, count in LOCATIONS}
    if len(loc_lines) != len(LOCATIONS):
        fail("report/creds.txt must have exactly %d location lines, found %d"
             % (len(LOCATIONS), len(loc_lines)))
    else:
        bad = [l for l in loc_lines if digest(l) not in expected]
        if bad:
            fail("a location line does not match the true path and occurrence count: %r" % bad[0])
        else:
            ok("every location line matches the true path and occurrence count")

    # 3b. the key_id line: first 8 chars of the original key, nothing more
    # (accepted spellings held as digests; a longer value is rejected here
    # and the full key is rejected outright by 3c below)
    KEY_ID_DIGESTS = {
    'f46d94c601eb5eebd38a4912bd567f8ccd73f753d24bd3bbfb8af79c5c4ad9ed',  # key_id: <prefix>
    'e39d2de18d98d9baa412e266d1f9eeffad34ba07b67217ab99df8823b97fc331',  # key_id = <prefix>
}
    if len(key_id_lines) != 1:
        fail("report/creds.txt must have exactly one key_id line, found %d"
             % len(key_id_lines))
    elif digest(key_id_lines[0]) not in KEY_ID_DIGESTS:
        fail("the key_id line does not match the accepted form (the first 8 "
             "chars of the original key, nothing more)")
    else:
        ok("the key_id line carries the key prefix only")

    # 3c. the full original key must never appear in the report
    report_text = open(rpath, encoding="utf-8").read()
    if ORIGINAL_KEY_DIGESTS & key_aware_variants(report_text.encode("utf-8")):
        fail("the full original key value appears in report/creds.txt")
    else:
        ok("the full original key value does not appear in the report")

    if FAILS:
        print("check: %d failure(s)" % len(FAILS))
        sys.exit(1)
    print("check: credential scrub verified")


if __name__ == "__main__":
    main()
