#!/usr/bin/env python
"""Acceptance gate for the sudoers typo triage.

The intended rule is re-derived here from docs/intent.md (the code block of
the approved rule section), never stored as a constant, so a report that
invents its own rule fails. The phrases describing the original typo are
held as SHA-256 digests so this file does not hand the wording to anyone
who reads it.

This file is part of the scenario definition - editing it is not a fix.
Run: python3 check.py
"""
import hashlib
import os
import re
import sys

BASE = os.path.dirname(os.path.abspath(__file__))
FAILS = []


def digest(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def ok(message):
    print("PASS -", message)


def fail(message):
    print("FAIL -", message)
    FAILS.append(message)


def read(path):
    with open(os.path.join(BASE, path), encoding="utf-8") as fh:
        return fh.read()


def parse_report():
    """Return {key: [values]} for every `key = value` line of the report."""
    path = os.path.join(BASE, "report", "triage.txt")
    if not os.path.isfile(path):
        fail("report/triage.txt is missing")
        return {}
    facts = {}
    for raw in read("report/triage.txt").splitlines():
        line = raw.strip()
        match = re.match(r"^([a-z_][a-z0-9_]*)\s*=\s*(.*?)\s*$", line)
        if match:
            facts.setdefault(match.group(1), []).append(match.group(2))
    return facts


def accepted(value):
    """Every spelling of a value the report may legitimately use."""
    value = value.strip().strip("`").strip()
    variants = {value, value.lstrip("./")}
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
        inner = value[1:-1]
        variants.update({inner, inner.lstrip("./")})
    return {digest(v) for v in variants if v}


def check_value(facts, key, value):
    """A single-answer fact: every occurrence must carry the derived value."""
    values = facts.get(key, [])
    if not values:
        fail("%s = ... is missing from report/triage.txt" % key)
        return
    if value is None:
        fail("%s could not be derived from the tree; the data was tampered with" % key)
        return
    want = accepted(value)
    for found in values:
        if not accepted(found) & want:
            fail("%s does not match the evidence" % key)
            return
    ok("%s matches the evidence" % key)


def derive_intended_rule():
    """The approved rule line, taken verbatim from docs/intent.md."""
    text = read("docs/intent.md")
    for block in re.findall(r"^\x20{4}(deploy[^\n]*)$", text, re.M):
        line = block.strip()
        if line.startswith("deploy") and "NOPASSWD" in line:
            return line
    return None


def main():
    intended = derive_intended_rule()
    if intended is None:
        print("FAIL - the approved rule could not be re-derived from docs/intent.md")
        sys.exit(1)

    # 1. the drop-in must contain exactly the intended rule, and the typo must be gone
    dropin_path = os.path.join(BASE, "etc/sudoers.d/deploy")
    if not os.path.isfile(dropin_path):
        fail("etc/sudoers.d/deploy is missing")
    else:
        dropin = read("etc/sudoers.d/deploy")
        live = [l.strip() for l in dropin.splitlines()
                if l.strip() and not l.lstrip().startswith("#")]
        if live != [intended]:
            fail("etc/sudoers.d/deploy must contain exactly one active line, "
                 "identical to the approved rule from docs/intent.md")
        else:
            ok("etc/sudoers.d/deploy matches the approved rule exactly")
        if re.search(r"NOPASSWD:\s*ALL\b", dropin):
            fail("the dangerous NOPASSWD: ALL grant is still present")
        else:
            ok("no NOPASSWD: ALL grant remains in the drop-in")

    # 2. the report keys
    facts = parse_report()
    check_value(facts, "intended_command", "/usr/bin/systemctl restart app"
                if intended != "deploy ALL=(deploy) NOPASSWD: /usr/bin/systemctl restart app" else intended.split(": ", 1)[1])
    # fixed short phrases describing the original typo: digests only
    PHRASES = {
        "typo_rule_effect": {
            "passwordless root for deploy",
            "passwordless root for the deploy account",
            "full passwordless root for deploy",
        },
        "risk_level": {
            "critical",
            "high",
        },
        "fixed_file": {
            "etc/sudoers.d/deploy",
            "./etc/sudoers.d/deploy",
        },
    }
    for key, spellings in PHRASES.items():
        values = facts.get(key, [])
        if not values:
            fail("%s = ... is missing from report/triage.txt" % key)
            continue
        want = set()
        for s in spellings:
            want.add(digest(s))
        good = True
        for found in values:
            f = found.strip().strip("`").strip()
            if digest(f) not in want and digest(f.lower()) not in want:
                good = False
        if good:
            ok("%s matches the accepted wording" % key)
        else:
            fail("%s does not match the accepted wording" % key)

    check_value(facts, "legacy_untouched", "yes")
    for key in sorted(facts):
        if key not in ("typo_rule_effect", "intended_command", "risk_level",
                       "fixed_file", "legacy_untouched"):
            fail("unexpected key %s in the report (only the five keys from the brief)" % key)

    if FAILS:
        print("check: %d failure(s)" % len(FAILS))
        sys.exit(1)
    print("check: triage verified")


if __name__ == "__main__":
    main()
