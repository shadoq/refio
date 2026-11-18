"""Summarise a request log: one CSV line per request (ts,user,status,bytes)."""


def parse_line(line):
    parts = line.strip().split(",")
    if len(parts) != 4:
        return None
    ts, user, status, size = parts
    try:
        return {"ts": ts, "user": user, "status": int(status), "bytes": int(size)}
    except ValueError:
        return None


def summarize(path):
    """Return per-user totals, requests per hour of day and the count of unreadable lines."""
    users = {}
    hourly = {}
    skipped = 0
    with open(path, encoding="utf-8") as handle:
        next(handle, None)  # header
        for line in handle:
            row = parse_line(line)
            if row is None:
                skipped += 1
                continue
            entry = users.setdefault(row["user"], {"requests": 0, "bytes": 0, "errors": 0})
            entry["requests"] += 1
            entry["bytes"] += row["bytes"]
            if row["status"] >= 500:
                entry["errors"] += 1
            hour = row["ts"][11:13]
            hourly[hour] = hourly.get(hour, 0) + 1

    return {"users": users, "hourly": hourly, "skipped": skipped}
