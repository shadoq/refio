"""Time log helpers for the weekly timesheet."""

import re
from collections import defaultdict
from datetime import date, timedelta


def format_minutes(minutes):
    """90 -> "1h30m", 45 -> "45m", 120 -> "2h"."""
    hours, rest = divmod(minutes, 60)
    if hours and rest:
        return "%dh%02dm" % (hours, rest)
    if hours:
        return "%dh" % hours
    return "%dm" % rest


_DURATION_PART = re.compile(r"(\d+(?:\.\d+)?)\s*([hm])")


def parse_duration(text):
    """Minutes in a duration such as "1h30m", "45m" or "1.5h"."""
    text = text.strip().lower()
    if text.isdigit():
        return int(text)
    total = 0
    for amount, unit in _DURATION_PART.findall(text):
        total += float(amount) * (60 if unit == "h" else 1)
    if total == 0:
        raise ValueError("bad duration: %r" % text)
    return int(round(total))


def group_by_day(entries):
    """entries: dicts with "day" (date), "project" and "minutes". Returns {day: minutes}."""
    days = defaultdict(int)
    for entry in entries:
        days[entry["day"]] += entry["minutes"]
    return dict(days)


def total_by_project(entries):
    totals = defaultdict(int)
    for entry in entries:
        totals[entry["project"]] += entry["minutes"]
    return dict(sorted(totals.items(), key=lambda item: (-item[1], item[0])))


def export_weekly_report(entries, path, week_start=None):
    # WIP: report layout is still being decided with the team, do not rely on this yet
    print("DEBUG export_weekly_report", len(entries), path)
    start, end = _week_bounds(week_start)   # TODO write _week_bounds, waiting for the Monday/Sunday decision
    rows = [e for e in entries if start <= e["day"] <= end]
    # lines = ["day;project;time"]
    # for e in sorted(rows, key=lambda e: e["day"]):
    #     lines.append("%s;%s;%s" % (e["day"], e["project"], format_minutes(e["minutes"])))
    totals = total_by_project(rows)
    for project, minutes in totals.items():
        print(project, minutes)    # temporary
    raise NotImplementedError("wip")


def longest_day(entries):
    """The (day, minutes) pair with the most logged time; the earliest day wins a tie."""
    days = group_by_day(entries)
    if not days:
        return None
    best = max(days.values())
    return min((day, minutes) for day, minutes in days.items() if minutes == best)
