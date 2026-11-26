#!/usr/bin/env python3
"""Acceptance gate: re-derive every number of report/dhcp-inventory.txt from
etc/dhcp/dhcpd.conf. Comments are stripped first; hosts, fixed-prefix6
declarations and dynamic ranges are then parsed, and all address arithmetic
goes through the ipaddress module, never string guesses."""
import ipaddress
import re
import sys

KEY_RE = re.compile(r"^([a-z0-9_]+) = (.+)$")
EXPECTED_KEYS = ["host_count", "ipv6_prefix_len", "dynamic_range_count",
                 "first_dynamic_ip", "last_dynamic_ip", "largest_range_size"]
HOST_RE = re.compile(r"\bhost\s+\S+\s*\{")
PREFIX6_RE = re.compile(r"fixed-prefix6\s+([0-9A-Fa-f:]+/\d+)")
RANGE_RE = re.compile(r"\brange\s+(\d+\.\d+\.\d+\.\d+)\s+(\d+\.\d+\.\d+\.\d+)\s*;")


def strip_comments(text):
    return "\n".join(line.split("#", 1)[0] for line in text.splitlines())


def rederive():
    with open("etc/dhcp/dhcpd.conf", encoding="utf-8") as fh:
        body = strip_comments(fh.read())
    hosts = len(HOST_RE.findall(body))
    plens = {ipaddress.IPv6Network(p).prefixlen for p in PREFIX6_RE.findall(body)}
    ranges = []
    for start, end in RANGE_RE.findall(body):
        a = ipaddress.IPv4Address(start)
        b = ipaddress.IPv4Address(end)
        if int(b) < int(a):
            continue
        ranges.append((a, b))
    if hosts == 0 or len(plens) != 1 or not ranges:
        print("FAIL: internal check - the configuration does not derive a "
              "consistent inventory (scenario drift)")
        sys.exit(1)
    first = min(a for a, _ in ranges)
    last = max(b for _, b in ranges)
    largest = max(int(b) - int(a) + 1 for a, b in ranges)
    return {
        "host_count": str(hosts),
        "ipv6_prefix_len": str(plens.pop()),
        "dynamic_range_count": str(len(ranges)),
        "first_dynamic_ip": str(first),
        "last_dynamic_ip": str(last),
        "largest_range_size": str(largest),
    }


def main() -> int:
    expected = rederive()
    try:
        with open("report/dhcp-inventory.txt", encoding="utf-8") as fh:
            raw = fh.read()
    except OSError:
        print("FAIL: report/dhcp-inventory.txt is missing")
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
                problems.append("%s does not match the configuration" % key)
    if problems:
        print("FAIL: " + "; ".join(problems))
        return 1
    print("PASS: all six inventory numbers match the dhcpd configuration")
    return 0


if __name__ == "__main__":
    sys.exit(main())
