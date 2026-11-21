#!/usr/bin/env python3
"""Acceptance gate for the DST-safe meeting shift.

Replays the drift reported against both 2024 Europe/Warsaw transitions (spring
forward on 2024-03-31, fall back on 2024-10-27) plus the no-transition
regressions. Exit 0 only when every check passes. This file is part of the
scenario definition - editing it is not a fix.

Run: python3 checks.py
"""
import os
import sys
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

BASE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, BASE)

from scheduler import describe, shift_meeting, weekly_slots  # noqa: E402

WARSAW = ZoneInfo("Europe/Warsaw")
UTC = ZoneInfo("UTC")

failures = []


def check(name, ok):
    print(("PASS" if ok else "FAIL"), "-", name)
    if not ok:
        failures.append(name)


def warsaw(*args):
    return datetime(*args, tzinfo=WARSAW)


# --- the reported drift: spring forward, 2024-03-31 ----------------------------
shifted = shift_meeting(warsaw(2024, 3, 25, 9, 30), 7)
check("weekly meeting keeps its 09:30 slot across spring forward",
      shifted == warsaw(2024, 4, 1, 9, 30))
check("the shifted meeting lands on the CEST offset",
      shifted.utcoffset() == timedelta(hours=2))

# --- fall back, 2024-10-27 ------------------------------------------------------
shifted = shift_meeting(warsaw(2024, 10, 21, 9, 30), 7)
check("weekly meeting keeps its 09:30 slot across fall back",
      shifted == warsaw(2024, 10, 28, 9, 30))
check("the shifted meeting lands on the CET offset",
      shifted.utcoffset() == timedelta(hours=1))

# --- naive input is read as Warsaw wall time ------------------------------------
shifted = shift_meeting(datetime(2024, 3, 30, 9, 30), 1)
check("naive input is treated as local wall time",
      shifted == warsaw(2024, 3, 31, 9, 30))

# --- aware input in another zone -----------------------------------------------
shifted = shift_meeting(datetime(2024, 3, 25, 8, 30, tzinfo=UTC), 7)
check("aware input is converted before shifting",
      shifted == warsaw(2024, 4, 1, 9, 30))

# --- no transition in range: behaviour must not change -------------------------
check("shift without a transition is unchanged",
      shift_meeting(warsaw(2024, 1, 8, 9, 30), 7) == warsaw(2024, 1, 15, 9, 30))
check("shift by zero days is the identity",
      shift_meeting(warsaw(2024, 6, 1, 12, 0), 0) == warsaw(2024, 6, 1, 12, 0))

# --- the weekly series lines up again ------------------------------------------
slots = weekly_slots(warsaw(2024, 3, 18, 9, 30), 3)
check("a series straddling the boundary stays at 09:30",
      [s.strftime("%H:%M") for s in slots] == ["09:30", "09:30", "09:30"])
check("the series keeps the right offsets",
      [s.utcoffset() for s in slots]
      == [timedelta(hours=1), timedelta(hours=1), timedelta(hours=2)])

# --- the confirmation email reads the same slot --------------------------------
check("describe still prints the local slot",
      describe(warsaw(2024, 3, 25, 9, 30)) == "2024-03-25 09:30 CET")

print()
if failures:
    print(f"checks: {len(failures)} check(s) FAILED: " + "; ".join(failures))
    sys.exit(1)
print("checks: all checks passed")
