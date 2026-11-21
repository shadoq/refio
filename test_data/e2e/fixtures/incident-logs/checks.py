#!/usr/bin/env python3
"""Acceptance gate: re-derive the five incident facts from the logs and match the report.

The report must contain the key=value lines with single spaces around '='. Everything is
recomputed from logs/auth.log, so the gate cannot be satisfied by a lucky guess in a stale
report: it checks exactly what the logs say.
"""
import re
import sys

EXPECTED = {
    "attacker_ip": "198.51.100.23",
    "compromised_account": "svc_backup",
    "first_success": "02:14:37",
    "escalation_command": "/bin/cat /etc/shadow",
    "persistence_user": "backdoor",
}


def derive_from_logs():
    facts = {}
    failed = {}
    with open("logs/auth.log", encoding="utf-8") as fh:
        for line in fh:
            ts = re.match(r"^\w{3} \d{1,2} (\d\d:\d\d:\d\d) \S+ ", line)
            ts = ts.group(1) if ts else None

            m = re.search(
                r"sshd\[\d+\]: Failed password for (?:invalid user )?(\S+) from (\S+) port", line
            )
            if m:
                failed.setdefault(m.group(2), []).append(m.group(1))

            m = re.search(
                r"sshd\[\d+\]: Accepted password for (\S+) from (\S+) port", line
            )
            if m:
                user, ip = m.group(1), m.group(2)
                if ip in failed and user in failed[ip]:
                    facts.setdefault("attacker_ip", ip)
                    facts.setdefault("compromised_account", user)
                    facts["first_success"] = ts

            m = re.search(r"sudo:\s+(\S+)\s+:.*COMMAND=(.+?)\s*$", line)
            if m and "useradd" in m.group(2):
                facts.setdefault("persistence_user", m.group(2).split()[-1])
            if "COMMAND=/bin/cat /etc/shadow" in line:
                facts.setdefault("escalation_command", "/bin/cat /etc/shadow")
    return facts


def main() -> int:
    derived = derive_from_logs()
    for key, expected in EXPECTED.items():
        if derived.get(key) != expected:
            print(f"FAIL: internal check - logs derive {key}={derived.get(key)!r}, "
                  f"gate expected {expected!r} (gate/scenario drift)")
            return 1
    try:
        with open("report/timeline.md", encoding="utf-8") as fh:
            report = fh.read()
    except OSError:
        print("FAIL: report/timeline.md is missing")
        return 1
    missing = []
    for key, value in EXPECTED.items():
        if not re.search(rf"^{re.escape(key)} = {re.escape(value)}\s*$", report, re.M):
            missing.append(f"{key} = {value}")
    if missing:
        print("FAIL: report lines missing or wrong: " + "; ".join(missing))
        return 1
    print("PASS: all five incident facts present and consistent with the logs")
    return 0


if __name__ == "__main__":
    sys.exit(main())
