#!/usr/bin/env python3
"""Acceptance gate: recompute every number of report/error-budget.txt from
logs/access.log (combined format) and compare exactly. Nothing is hard-coded;
the log is the only source of truth."""
import re
import sys

LINE_RE = re.compile(
    r'^(?P<client>\S+) \S+ \S+ \[[^]]+\] "[^"]*" (?P<status>\d{3}) (?P<size>\d+) '
)

KEY_RE = re.compile(r"^([a-z0-9_]+) = (.+)$")
EXPECTED_KEYS = ["total_requests", "http_5xx", "five_x_ratio", "http_499",
                 "unique_clients_5xx", "top_5xx_client"]


def recompute():
    total = 0
    c5 = 0
    c499 = 0
    per_client = {}
    with open("logs/access.log", encoding="utf-8") as fh:
        for line in fh:
            m = LINE_RE.match(line)
            if not m:
                continue
            total += 1
            status = int(m.group("status"))
            client = m.group("client")
            if status >= 500:
                c5 += 1
                per_client[client] = per_client.get(client, 0) + 1
            elif status == 499:
                c499 += 1
    if not total or not per_client:
        print("FAIL: internal check - log derives no traffic (scenario drift)")
        sys.exit(1)
    top = sorted(per_client.items(), key=lambda kv: (-kv[1], kv[0]))
    if len(top) > 1 and top[0][1] == top[1][1]:
        print("FAIL: internal check - top 5xx client is not unique (scenario drift)")
        sys.exit(1)
    return {
        "total_requests": str(total),
        "http_5xx": str(c5),
        "five_x_ratio": "%.3f" % (c5 / total),
        "http_499": str(c499),
        "unique_clients_5xx": str(len(per_client)),
        "top_5xx_client": top[0][0],
    }


def main() -> int:
    expected = recompute()
    try:
        with open("report/error-budget.txt", encoding="utf-8") as fh:
            raw = fh.read()
    except OSError:
        print("FAIL: report/error-budget.txt is missing")
        return 1
    got = {}
    order = []
    for lineno, line in enumerate(raw.splitlines(), 1):
        if not line.strip():
            continue
        m = KEY_RE.match(line)
        if not m:
            print("FAIL: line %d is not a 'key = value' line: %r"
                  % (lineno, line))
            return 1
        key, value = m.group(1), m.group(2)
        if key in got:
            print("FAIL: key %s appears twice" % key)
            return 1
        got[key] = value
        order.append(key)
    problems = []
    if set(order) != set(EXPECTED_KEYS):
        problems.append("keys are exactly %s (got %s)"
                        % (sorted(EXPECTED_KEYS), sorted(order)))
    else:
        for key in EXPECTED_KEYS:
            if got[key] != expected[key]:
                problems.append("%s is %r, expected %r"
                                % (key, got[key], expected[key]))
        if not re.match(r"^\d+\.\d{3}$", got["five_x_ratio"]):
            problems.append("five_x_ratio must carry exactly 3 decimals")
    if problems:
        print("FAIL: " + "; ".join(problems))
        return 1
    print("PASS: all six error-budget numbers match the log")
    return 0


if __name__ == "__main__":
    sys.exit(main())
