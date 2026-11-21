"""Summarise a CSV activity export into JSON. See SPEC.md."""
import csv
import json
import re
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone

TICKET = re.compile(r"[A-Za-z]{2,5}-\d+")


def _parse_ts(text):
    text = text.strip()
    try:
        return datetime.strptime(text, "%Y-%m-%dT%H:%M:%S%z").astimezone(timezone.utc)
    except ValueError:
        return None


def summarize(path):
    total = 0
    skipped = 0
    day_events = Counter()
    day_users = defaultdict(set)
    actions = Counter()
    hours = Counter()
    tickets = Counter()
    durations = defaultdict(list)
    with open(path, newline="", encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            ts = _parse_ts(row.get("timestamp") or "")
            user = (row.get("user") or "").strip().lower()
            action = (row.get("action") or "").strip().lower()
            if ts is None or not user or not action:
                skipped += 1
                continue
            total += 1
            day = ts.strftime("%Y-%m-%d")
            day_events[day] += 1
            day_users[day].add(user)
            actions[action] += 1
            hours[ts.hour] += 1
            for tid in TICKET.findall(row.get("detail") or ""):
                tickets[tid] += 1
            try:
                durations[action].append(int((row.get("duration_ms") or "").strip()))
            except ValueError:
                pass
    top = sorted(actions.items(), key=lambda kv: (-kv[1], kv[0]))[:3]
    busiest = min(hours, key=lambda h: (-hours[h], h)) if hours else None
    return {
        "total_events": total,
        "skipped": skipped,
        "per_day": {d: {"events": day_events[d], "unique_users": len(day_users[d])}
                    for d in sorted(day_events)},
        "top_actions": [[a, n] for a, n in top],
        "busiest_hour_utc": busiest,
        "tickets": dict(sorted(tickets.items())),
        "avg_duration_ms": {a: round(sum(v) / len(v), 1) for a, v in sorted(durations.items()) if v},
    }


def main(argv):
    if len(argv) != 2:
        print("usage: summarize.py <input.csv> <output.json>", file=sys.stderr)
        return 2
    result = summarize(argv[0])
    with open(argv[1], "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(result, indent=2, sort_keys=True) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
