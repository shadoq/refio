#!/usr/bin/env python
"""Acceptance gate for the SIEM detection tuning.

Checks:
  1. rules_test.py passes (run as a subprocess): benign fires zero rules,
     attack fires all three;
  2. only the threshold NUMBERS changed in rules/*.yaml (names, match
     patterns, windows, file count all still as shipped) and the threshold
     numbers on disk match an accepted TUNED value (digests below), not the
     shipped too-low ones;
  3. the report lists old -> new for every rule with the true original
     thresholds (held as SHA-256 digests since the originals are edited
     away), plus benign_fires = 0 and attack_fires = 3.

This file is part of the scenario definition - editing it is not a fix.
Run: python3 check.py
"""
import glob
import hashlib
import os
import re
import subprocess
import sys

BASE = os.path.dirname(os.path.abspath(__file__))
FAILS = []

# SHA-256 digests of the original "rule: old -> new" report lines with the
# accepted new values (the correct tuned thresholds).
def digest(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


TUNING_DIGESTS = {digest(s) for s in {
    "auth-failures.yaml: 5 -> 8",
    "auth-failures.yaml: 5 -> 9",
    "auth-failures.yaml: 5 -> 10",
    "privilege-escalations.yaml: 4 -> 5",
    "privilege-escalations.yaml: 4 -> 6",
    "privilege-escalations.yaml: 4 -> 7",
    "sensitive-file-reads.yaml: 3 -> 5",
    "sensitive-file-reads.yaml: 3 -> 6",
    "sensitive-file-reads.yaml: 3 -> 7",
}}
TUNING_DIGESTS |= {digest(s.replace(".yaml: ", ": ")) for s in {
    "auth-failures.yaml: 5 -> 8",
    "auth-failures.yaml: 5 -> 9",
    "auth-failures.yaml: 5 -> 10",
    "privilege-escalations.yaml: 4 -> 5",
    "privilege-escalations.yaml: 4 -> 6",
    "privilege-escalations.yaml: 4 -> 7",
    "sensitive-file-reads.yaml: 3 -> 5",
    "sensitive-file-reads.yaml: 3 -> 6",
    "sensitive-file-reads.yaml: 3 -> 7",
}}

# SHA-256 digests of the accepted tuned "threshold: N" lines per rule
# file (the same accepted values as the report digests above). A rules
# tree still at the shipped too-low thresholds fails here even when the
# report alone is correct, because the gate asserts the tuned state of
# the rule files on disk, not only that rules_test.py passes.
THRESHOLD_DIGESTS = {
    'auth-failures.yaml': {
        '216c006b06f36f8b8bb2098a1393aad4bfd8cdf860a9eec47dc9b2791207c040', '2371696faa554c304cf1ef7ca409a27dc55c5c46a547c1335131d3eb0ec53093',
        '35163f1823523851b1a53e9bf9282d35d5a3f85f3587789ad1876df0d1bc7ad8', '3e3054df27d9f458eef38590e2bc0115b358560fd7aa45cc8887a9f7f0c57d22',
        '97e55a914ecf7a9a87ddc741d9b9cadba96a035373170a9af7e03c47ad52d1df', 'd7b00cab90e6c2ee56d064ed1619d61e38e554a62aff1bc449a96b0c00792dc3',
        'e3eb856d468245c0af29c7d7299314e11fe182a6eabaf3c27b4b0de3dfe8d51d', 'eabd8bd0bbe171fae2cb5dcdd7863c94945679cf83d593327df459c4d8964d8c',
        'fc49d23a957a0be78671834d1364081f19f8f4ad618bce48107827e8646836c6',
    },
    'privilege-escalations.yaml': {
        '11bf3b96d9ef758a1deb473b522dfa1cd6186de3b50f3fe4761ca70967908f54', '1c3ae6af32c6d3f09c0901ab08b6c82535b306328464f76f4b39d04a980e7cdf',
        '8d1abf0a8454f2e9e79c6ea043be4afe842139c148dbf0512e372fced2849e07', '93e2d6205432c5ff45c05e12d804a2d3a4fbfc20f25553ad0baaef2ca9bb0d1c',
        'b3e49a5026aafe68544aedecb87277e56ef844b6a5d5e1e420fecda5f84062fe', 'd2ded2ee3fed0e19ab9b5213b42add224de1c3ab96a603f700c8de184e004ac3',
        'd344a1a87b454d0af8292a599541dd2bdf2f0f5c09978bc9cfbf335322a851a8', 'd739bb94026035fdd40593481764449ac3afae7c8b9a83e43795de90a2ef74f9',
        'e85549525e16edb3836c32bbeab72e14445594959a089ced625ff11d7458a408',
    },
    'sensitive-file-reads.yaml': {
        '11bf3b96d9ef758a1deb473b522dfa1cd6186de3b50f3fe4761ca70967908f54', '1c3ae6af32c6d3f09c0901ab08b6c82535b306328464f76f4b39d04a980e7cdf',
        '8d1abf0a8454f2e9e79c6ea043be4afe842139c148dbf0512e372fced2849e07', '93e2d6205432c5ff45c05e12d804a2d3a4fbfc20f25553ad0baaef2ca9bb0d1c',
        'b3e49a5026aafe68544aedecb87277e56ef844b6a5d5e1e420fecda5f84062fe', 'd2ded2ee3fed0e19ab9b5213b42add224de1c3ab96a603f700c8de184e004ac3',
        'd344a1a87b454d0af8292a599541dd2bdf2f0f5c09978bc9cfbf335322a851a8', 'd739bb94026035fdd40593481764449ac3afae7c8b9a83e43795de90a2ef74f9',
        'e85549525e16edb3836c32bbeab72e14445594959a089ced625ff11d7458a408',
    },
}


def ok(message):
    print("PASS -", message)


def fail(message):
    print("FAIL -", message)
    FAILS.append(message)


def parse_rule(path):
    rule = {"match": {}}
    section = None
    for raw in open(path, encoding="utf-8"):
        line = raw.rstrip("\n")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        indent = len(line) - len(line.lstrip())
        key, _, value = line.strip().partition(":")
        value = value.strip()
        if indent == 0:
            section = key
            if key != "match":
                rule[key] = value
        elif section == "match":
            rule["match"][key] = value
    rule["threshold"] = int(rule.get("threshold", 0))
    return rule


def main():
    # 1. rules_test.py must pass
    proc = subprocess.run([sys.executable, os.path.join(BASE, "rules_test.py")],
                          capture_output=True, text=True, cwd=BASE, timeout=120)
    if proc.returncode != 0:
        fail("rules_test.py does not pass (exit %d): %s" % (proc.returncode, proc.stdout.strip()))
    else:
        ok("rules_test.py passes: " + proc.stdout.strip().splitlines()[-1])

    # 2. the rules are still the shipped three, structurally intact
    rule_paths = sorted(glob.glob(os.path.join(BASE, "rules", "*.yaml")))
    expected = {"auth-failures.yaml", "privilege-escalations.yaml",
                "sensitive-file-reads.yaml"}
    found = {os.path.basename(p) for p in rule_paths}
    if found != expected:
        fail("rules/ must contain exactly the three shipped rules; found %s" % sorted(found))
    else:
        ok("rules/ still contains the three shipped rules")
    for path in rule_paths:
        rule = parse_rule(path)
        if rule.get("threshold", 0) <= 0:
            fail("%s has no positive threshold (rule disabled?)" % os.path.basename(path))
    patterns_ok = True
    for path in rule_paths:
        rule = parse_rule(path)
        if not rule["match"] or "event" not in rule["match"]:
            fail("%s lost its match pattern" % os.path.basename(path))
            patterns_ok = False
    if patterns_ok:
        ok("all three rules kept their match patterns")

    # 2b. the rules on disk are actually TUNED: the "threshold: N" line of
    # each rule file matches an accepted tuned value (digests only, so the
    # plain numbers the agent must choose are not spelled out here). A rules
    # tree still at the shipped too-low thresholds fails here even when
    # rules_test.py passes and the report alone is correct.
    tuned_ok = True
    for path in rule_paths:
        name = os.path.basename(path)
        threshold_lines = [raw.rstrip("\n") for raw in open(path, encoding="utf-8")
                           if re.match(r"^\s*threshold\s*:", raw)]
        if len(threshold_lines) != 1:
            fail("%s must have exactly one threshold line (found %d)" % (name, len(threshold_lines)))
            tuned_ok = False
            continue
        if digest(threshold_lines[0]) not in THRESHOLD_DIGESTS.get(name, set()):
            fail("the threshold line of %s does not match an accepted tuned value" % name)
            tuned_ok = False
    if tuned_ok:
        ok("all three rule files are tuned to accepted thresholds")

    # 3. the report
    path = os.path.join(BASE, "report", "tuning.txt")
    if not os.path.isfile(path):
        print("FAIL - report/tuning.txt is missing")
        sys.exit(1)
    lines = [l.strip() for l in open(path, encoding="utf-8").read().splitlines() if l.strip()]
    tuning_lines = [l for l in lines if " -> " in l]
    kv = {}
    for l in lines:
        m = re.match(r"^([a-z_][a-z0-9_]*)\s*=\s*(.*?)\s*$", l)
        if m:
            kv.setdefault(m.group(1), []).append(m.group(2))

    if len(tuning_lines) != 3:
        fail("report/tuning.txt must have exactly three 'rule: old -> new' lines, found %d" % len(tuning_lines))
    else:
        bad = [l for l in tuning_lines if digest(l) not in TUNING_DIGESTS]
        if bad:
            fail("a tuning line does not match the original threshold and an accepted new value: %r" % bad[0])
        else:
            ok("all three tuning lines match the original thresholds and accepted new values")

    if kv.get("benign_fires") != ["0"]:
        fail("benign_fires = 0 is missing or wrong (report says %r)" % kv.get("benign_fires"))
    else:
        ok("benign_fires = 0 matches the rules_test result")
    if kv.get("attack_fires") != ["3"]:
        fail("attack_fires = 3 is missing or wrong (report says %r)" % kv.get("attack_fires"))
    else:
        ok("attack_fires = 3 matches the rules_test result")

    for key in sorted(kv):
        if key not in ("benign_fires", "attack_fires"):
            fail("unexpected key %s in the report" % key)

    if FAILS:
        print("check: %d failure(s)" % len(FAILS))
        sys.exit(1)
    print("check: tuning verified")


if __name__ == "__main__":
    main()
