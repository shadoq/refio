#!/usr/bin/env python3
"""Acceptance gate for the csvlite trailing-field fix.

Replays the corrupted billing export from the issue plus the visible unit
suite. Exit 0 only when every check passes. This file is part of the scenario
definition - editing it is not a fix.

Run: python3 checks.py
"""
import os
import subprocess
import sys

BASE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, BASE)

from csvlite import parse, pad_row, split_row  # noqa: E402

failures = []


def check(name, ok):
    print(("PASS" if ok else "FAIL"), "-", name)
    if not ok:
        failures.append(name)


# --- failure mode 1: the last field is dropped when it is empty ---------------
check("row ending on a separator keeps its empty last field",
      split_row("a,b,") == ["a", "b", ""])

check("row made only of separators is all empty fields",
      split_row(",,") == ["", "", ""])

check("single separator yields two empty fields",
      split_row(",") == ["", ""])

check("quoted empty last field survives",
      split_row('a,""') == ["a", ""])

check("empty row is a single empty field",
      split_row("") == [""])

# --- failure mode 2: the export job miscounts and misaligns -------------------
check("trailing commas no longer miscount fields",
      parse("h1,h2,h3\nsku-1,2,\nsku-2,1,5\n")
      == [["h1", "h2", "h3"], ["sku-1", "2", ""], ["sku-2", "1", "5"]])

check("pad_row lines the short row up with the header again",
      pad_row(split_row("sku-1,2,"), 4) == ["sku-1", "2", "", ""])

# --- regression: quoting behaviour must not change ----------------------------
check("quoted commas still belong to the field",
      split_row('"x,y",z') == ["x,y", "z"])

check("doubled quotes still unescape",
      split_row('"a""b",c') == ['a"b', 'c'])

check("inner empty fields still work",
      split_row("a,,c") == ["a", "", "c"])

check("plain rows still split",
      split_row("a,b,c") == ["a", "b", "c"])

# --- the visible unit suite must stay green ------------------------------------
proc = subprocess.run(
    [sys.executable, "-m", "unittest", "discover", "-s", "test"],
    cwd=BASE,
    capture_output=True,
    text=True,
)
check("visible unit tests stay green", proc.returncode == 0)

print()
if failures:
    print(f"checks: {len(failures)} check(s) FAILED: " + "; ".join(failures))
    sys.exit(1)
print("checks: all checks passed")
