#!/usr/bin/env python
"""rules_test.py - the provided regression test for the detection rules.

Enumerates rules/*.yaml itself (a rule that is disabled, renamed away or
deleted makes the run fail), runs every rule against both baselines and
exits 0 only when the benign baseline fires ZERO rules and the attack
baseline fires ALL of them.

This file is part of the scenario definition - editing it is not a fix.
Run: python3 rules_test.py
"""
import glob
import json
import os
import sys

BASE = os.path.dirname(os.path.abspath(__file__))

# minimal YAML subset reader for the rule files (name, match.event, window,
# threshold) - no external dependencies
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


def load_events(path):
    events = []
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line:
                events.append(json.loads(line))
    return events


def fires(rule, events):
    count = 0
    for e in events:
        ok = True
        for k, v in rule["match"].items():
            if str(e.get(k)) != v:
                ok = False
                break
        if ok:
            count += 1
    return count > rule["threshold"]


def main():
    rule_paths = sorted(glob.glob(os.path.join(BASE, "rules", "*.yaml")))
    if len(rule_paths) != 3:
        print("rules_test: expected 3 rules under rules/, found %d" % len(rule_paths))
        return 1
    rules = [(os.path.basename(p), parse_rule(p)) for p in rule_paths]

    benign = load_events(os.path.join(BASE, "baseline", "events-benign.ndjson"))
    attack = load_events(os.path.join(BASE, "baseline", "events-attack.ndjson"))

    failures = 0
    benign_fires = 0
    attack_fires = 0
    for name, rule in rules:
        b = fires(rule, benign)
        a = fires(rule, attack)
        benign_fires += 1 if b else 0
        attack_fires += 1 if a else 0
        if b:
            print("rules_test: %s FIRES on the benign baseline (false positive)" % name)
            failures += 1
        if not a:
            print("rules_test: %s does NOT fire on the attack baseline (miss)" % name)
            failures += 1
    print("rules_test: benign_fires=%d attack_fires=%d rules=%d"
          % (benign_fires, attack_fires, len(rules)))
    if failures:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
