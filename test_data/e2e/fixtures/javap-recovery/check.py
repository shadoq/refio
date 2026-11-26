#!/usr/bin/env python3
"""Acceptance check for the InterestCalculator source recovery.

Prints PASS and exits 0 when report/recovery.md names the three decompiler mistakes at the
right lines of decompiled/InterestCalculator.java and src/InterestCalculator.java compiles and
behaves exactly like the shipped class. The accepted mistake line numbers are stored as SHA-256
digests; the behaviour is compared by running the original class and the recompiled source on
a fixed grid of inputs (tier boundaries, leap days, zero, negative and large values) and
comparing the output byte for byte.
"""
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile

REPORT = "report/recovery.md"
SRC = "src/InterestCalculator.java"
DECOMPILED = "decompiled/InterestCalculator.java"

# The compile step needs a JDK (the jdk.compiler module); a JRE alone is not enough.
JAVAC = ["java", "-m", "jdk.compiler/com.sun.tools.javac.Main"]

AMOUNTS = [
    0, 1, -1, 99, -99, 99999, 100000, 100001, 123457, 324423, 333333, 500000,
    999999, 1000000, 1000001, 2500000, 9999999, 10000000, 10000001, 55555555,
    123456789, 999999999, 1234567890123, 9223372036854775807, -99999999999,
]
DAYS = [-5, 0, 1, 30, 364, 365, 366, 400, 700, 729, 730, 731, 1000, 2000]
YEARS = [1900, 2000, 2023, 2024, 2100]
CHUNK = 300

# sha256 of the accepted line number for each mistake.
LINE_DIGESTS = {
    "boundary": [
        "eb1e33e8a81b697b75855af6bfcdbcbf7cbbde9f94962ceaec1ed8af21f5a50f"
    ],
    "division": [
        "7688b6ef52555962d008fff894223582c484517cea7da49ee67800adc7fc8866"
    ],
    "ternary": [
        "6208ef0f7750c111548cf90b6ea1d0d0a66f6bff40dbef07cb45ec436263c7d6"
    ]
}

# Words the explanation of each mistake is expected to use (categories, not answers).
KEYWORDS = {
    "boundary": ["boundary", "invert", "condition", "comparison", "strict", "iflt",
                 "branch", "less than", "tier"],
    "division": ["integer", "division", "divid", "truncat", "round", "ldiv"],
    "ternary": ["ternary", "precedence", "parenthes", "conditional", "operator",
                "expression", "arm"],
}

MISTAKE_LINE = re.compile(r"^\s*(?:[-*]\s+|\d+\.\s+)?`?mistake`?\s*=\s*(\d+)\s*\|\s*(.+?)\s*$")


def sha(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def cases():
    for amount in AMOUNTS:
        for days in DAYS:
            for year in YEARS:
                yield (amount, days, year)


def run_batch(cp, batch):
    args = []
    for amount, days, year in batch:
        args.extend([str(amount), str(days), str(year)])
    r = subprocess.run(["java", "-cp", cp, "InterestCalculator"] + args,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if r.returncode != 0 or r.stderr:
        return None, "running InterestCalculator from %s failed (exit %d): %s" % (
            cp, r.returncode, (r.stderr or "").strip()[:200])
    return r.stdout, None


def check_report():
    try:
        with open(DECOMPILED, encoding="utf-8") as fh:
            dec_lines = fh.read().splitlines()
    except OSError:
        print("FAIL: %s not found in the project" % DECOMPILED)
        return None
    try:
        with open(REPORT, encoding="utf-8") as fh:
            report_lines = fh.read().splitlines()
    except OSError:
        print("FAIL: %s not found. Write it with three 'mistake = <line> | <explanation>' lines"
              % REPORT)
        return None

    found = []
    for raw in report_lines:
        m = MISTAKE_LINE.match(raw)
        if m:
            found.append((int(m.group(1)), m.group(2)))
    if not found:
        print("FAIL: no 'mistake = <line number> | <what the bytecode really does>' line in %s"
              % REPORT)
        return None

    by_category = {}
    for number, text in found:
        matched = None
        for category, digests in LINE_DIGESTS.items():
            if sha(str(number)) in digests:
                matched = category
                break
        if matched is None:
            print("FAIL: %d is not a line of decompiled/InterestCalculator.java that contains "
                  "one of the three mistakes (line numbers are 1-based from the top of the file)"
                  % number)
            return None
        if matched in by_category:
            print("FAIL: two mistake lines point at the same mistake (%s)" % matched)
            return None
        low = text.lower()
        if len(low) < 15:
            print("FAIL: the explanation for line %d is too short; say what the bytecode really "
                  "does, not just the line" % number)
            return None
        if number <= len(dec_lines) and text.strip() == dec_lines[number - 1].strip():
            print("FAIL: the explanation for line %d just quotes the decompiled line; describe "
                  "what the bytecode really does" % number)
            return None
        if not any(k in low for k in KEYWORDS[matched]):
            print("FAIL: the explanation for line %d does not describe the %s mistake in its "
                  "own words" % (number, matched))
            return None
        by_category[matched] = number
    missing = [c for c in LINE_DIGESTS if c not in by_category]
    if missing:
        print("FAIL: %s covers %d of the 3 mistakes; a 'mistake = <line> | ...' line is missing "
              "for: %s" % (REPORT, len(by_category), ", ".join(missing)))
        return None
    return by_category


def main():
    if check_report() is None:
        return 1
    if not os.path.isfile(SRC):
        print("FAIL: %s not found. Recover the source and write it there" % SRC)
        return 1

    tmp = tempfile.mkdtemp(prefix="recovery-check-")
    try:
        r = subprocess.run(JAVAC + ["-d", tmp, SRC], stdout=subprocess.PIPE,
                           stderr=subprocess.STDOUT, text=True)
        if r.returncode != 0:
            print("FAIL: %s does not compile with %s:\n%s"
                  % (SRC, " ".join(JAVAC), r.stdout.strip()[:400]))
            return 1

        all_cases = list(cases())
        for start in range(0, len(all_cases), CHUNK):
            batch = all_cases[start:start + CHUNK]
            want, err = run_batch(".", batch)
            if err:
                print("FAIL: %s (the shipped class must run; is a JDK java on PATH?)" % err)
                return 1
            got, err = run_batch(tmp, batch)
            if err:
                print("FAIL: the recovered class does not run on every input: %s" % err)
                return 1
            if want != got:
                w = want.splitlines()
                g = got.splitlines()
                for i in range(max(len(w), len(g))):
                    a = w[i] if i < len(w) else "<missing>"
                    b = g[i] if i < len(g) else "<missing>"
                    if a != b:
                        amount, days, year = batch[i]
                        print("FAIL: behaviour differs from the shipped class on input "
                              "amount=%d days=%d year=%d:\n  shipped:    %s\n  recovered:  %s"
                              % (amount, days, year, a, b))
                        return 1
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print("PASS: the three mistakes are reported at the right lines and the recovered source "
          "matches the shipped class on all %d inputs" % len(all_cases))
    return 0


if __name__ == "__main__":
    sys.exit(main())
