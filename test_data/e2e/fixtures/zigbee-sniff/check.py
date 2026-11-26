#!/usr/bin/env python
"""Acceptance gate for the zigbee sniff report.

All five values are re-derived here from both capture files: frame counts
per channel, the PAN id carried by every frame of each channel, and the
total rejoin requests (cluster_id 0x0034 addressed to the 0xfffd join
address). These are pure ndjson files: every line is a JSON object, there
is no header line.

This file is part of the scenario definition - editing it is not a fix.
Run: python3 check.py
"""
import json
import os
import re
import sys

BASE = os.path.dirname(os.path.abspath(__file__))
CAP = os.path.join(BASE, "captures")
FAILS = []


def ok(message):
    print("PASS -", message)


def fail(message):
    print("FAIL -", message)
    FAILS.append(message)


def load(fname):
    frames = []
    for line in open(os.path.join(CAP, fname), encoding="utf-8"):
        line = line.strip()
        if line:
            frames.append(json.loads(line))
    return frames


def main():
    ch11 = load("channel11.ndjson")
    ch15 = load("channel15.ndjson")

    def pan(frames):
        pans = {f["pan_id"] for f in frames}
        return pans.pop() if len(pans) == 1 else None

    def rejoins(frames):
        return sum(1 for f in frames
                   if f["cluster_id"] == "0x0034" and f["dst"] == "fffd")

    want = {
        "frames_ch11": str(len(ch11)),
        "frames_ch15": str(len(ch15)),
        "pan_ch11": pan(ch11),
        "pan_ch15": pan(ch15),
        "rejoins_total": str(rejoins(ch11) + rejoins(ch15)),
    }
    for key in ("pan_ch11", "pan_ch15"):
        if want[key] is None:
            print("FAIL - %s could not be derived; the capture was tampered with" % key)
            sys.exit(1)

    path = os.path.join(BASE, "report", "zigbee.txt")
    if not os.path.isfile(path):
        print("FAIL - report/zigbee.txt is missing")
        sys.exit(1)
    facts = {}
    for raw in open(path, encoding="utf-8").read().splitlines():
        line = raw.strip()
        m = re.match(r"^([a-z_][a-z0-9_]*)\s*=\s*(.*?)\s*$", line)
        if m:
            facts.setdefault(m.group(1), []).append(m.group(2))

    for key, value in want.items():
        values = facts.get(key, [])
        if not values:
            fail("%s = ... is missing from report/zigbee.txt" % key)
            continue
        if len(values) > 1:
            fail("%s appears %d times; exactly one line per key is required" % (key, len(values)))
            continue
        if values[0].strip() != value:
            fail("%s does not match the captures (report says %r)" % (key, values[0]))
        else:
            ok("%s matches the captures" % key)

    for key in sorted(facts):
        if key not in want:
            fail("unexpected key %s in the report (only the five keys from the brief)" % key)

    if FAILS:
        print("check: %d failure(s)" % len(FAILS))
        sys.exit(1)
    print("check: zigbee report verified against both captures")


if __name__ == "__main__":
    main()
