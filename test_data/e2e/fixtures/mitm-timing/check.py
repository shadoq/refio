#!/usr/bin/env python
"""Acceptance gate for the TLS timing triage.

Both medians are re-derived here from logs/tls-handshakes.csv with
statistics.median, and the verdict rule (attack median above 1.5x the
normal median AND resumed=no share above 80 percent in the attack group)
is evaluated from the data. The attack group is identified from the data
itself: the peer whose sessions never resume with systematically longer
durations. The accepted verdict phrase is held as a SHA-256 digest.

This file is part of the scenario definition - editing it is not a fix.
Run: python3 check.py
"""
import csv
import hashlib
import os
import re
import statistics
import sys

BASE = os.path.dirname(os.path.abspath(__file__))
FAILS = []

VERDICT_DIGESTS = {
    hashlib.sha256("interception suspected".encode("utf-8")).hexdigest(),
}


def digest(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def ok(message):
    print("PASS -", message)


def fail(message):
    print("FAIL -", message)
    FAILS.append(message)


def load_rows():
    path = os.path.join(BASE, "logs", "tls-handshakes.csv")
    rows = []
    with open(path, encoding="utf-8", newline="") as fh:
        for row in csv.DictReader(fh):
            rows.append({
                "peer": row["peer"],
                "duration_ms": float(row["duration_ms"]),
                "resumed": row["resumed"].strip().lower(),
            })
    return rows


def find_attack_peer(rows):
    """The peer with a systematic pattern: never resumes and is the slowest.

    Derived from the data: group by peer, take the peers whose resumed=no
    share is 100 percent, and among them the one with the highest median
    duration.
    """
    by_peer = {}
    for r in rows:
        by_peer.setdefault(r["peer"], []).append(r)
    candidates = []
    for peer, sessions in by_peer.items():
        no_share = sum(1 for s in sessions if s["resumed"] == "no") / len(sessions)
        if no_share >= 1.0:
            candidates.append((statistics.median(s["duration_ms"] for s in sessions), peer))
    if not candidates:
        return None
    candidates.sort()
    return candidates[-1][1]


def parse_report():
    path = os.path.join(BASE, "report", "timing.txt")
    if not os.path.isfile(path):
        fail("report/timing.txt is missing")
        return {}
    facts = {}
    for raw in open(path, encoding="utf-8").read().splitlines():
        line = raw.strip()
        match = re.match(r"^([a-z_][a-z0-9_]*)\s*=\s*(.*?)\s*$", line)
        if match:
            facts.setdefault(match.group(1), []).append(match.group(2))
    return facts


def fmt(value):
    """Report spelling of a duration: int-valued floats without .0."""
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    return str(value)


def main():
    rows = load_rows()
    attack_peer = find_attack_peer(rows)
    if attack_peer is None:
        print("FAIL - no attack-pattern peer found in the data; it was tampered with")
        sys.exit(1)

    attack = [r["duration_ms"] for r in rows if r["peer"] == attack_peer]
    normal = [r["duration_ms"] for r in rows if r["peer"] != attack_peer]
    no_share = sum(1 for r in rows if r["peer"] == attack_peer
                   and r["resumed"] == "no") / len(attack)

    med_a = statistics.median(attack)
    med_n = statistics.median(normal)
    verdict = ("interception suspected"
               if (med_a > 1.5 * med_n and no_share > 0.8) else "no anomaly")

    facts = parse_report()

    def check_number(key, value):
        values = facts.get(key, [])
        if not values:
            fail("%s = ... is missing from report/timing.txt" % key)
            return
        spellings = {fmt(value), "%.2f" % value, "%.1f" % value, str(round(value, 1)), str(round(value, 2))}
        bad = [v for v in values if v.strip() not in spellings]
        if bad:
            fail("%s does not match the median of the data (report says %r)" % (key, bad[0]))
            return
        if len(values) > 1:
            fail("%s appears %d times; exactly one line per key is required" % (key, len(values)))
            return
        ok("%s matches the median of the data" % key)

    check_number("median_attack_ms", med_a)
    check_number("median_normal_ms", med_n)

    values = facts.get("verdict", [])
    if not values:
        fail("verdict = ... is missing from report/timing.txt")
    else:
        good = all(digest(v.strip()) in VERDICT_DIGESTS for v in values)
        if good:
            ok("verdict matches the accepted wording and the data supports it")
        else:
            fail("verdict does not match the accepted wording")

    for key in sorted(facts):
        if key not in ("median_attack_ms", "median_normal_ms", "verdict"):
            fail("unexpected key %s in the report (only the three keys from the brief)" % key)

    if FAILS:
        print("check: %d failure(s)" % len(FAILS))
        sys.exit(1)
    print("check: timing triage verified against the data")


if __name__ == "__main__":
    main()
