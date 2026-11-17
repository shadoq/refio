#!/usr/bin/env python3
"""Acceptance gate for the process-autopsy exercise.

Re-derives every fact from ps-snapshot.txt and dmesg-oom.txt (the evidence), then
matches them against the `key = value` lines in report/proc-report.md. Nothing is
hard-coded that the evidence does not imply.
"""
import re
import sys

EXPECTED = {
    "hog_pid": "1003",
    "zombie_pid": "1017",
    "zombie_parent": "1005",
    "oom_victim": "1013",
    "worker_count": "3",
    "worker_parent": "990",
}


def derive(ps_text: str, dmesg_text: str) -> dict:
    rows = []
    for line in ps_text.splitlines():
        m = re.match(r"\s*(\d+)\s+(\d+)\s+(\S+)\s+([\d.]+)\s+([\d.]+)\s+\S+\s+(.*)$", line)
        if m:
            rows.append(
                {
                    "pid": int(m.group(1)),
                    "ppid": int(m.group(2)),
                    "stat": m.group(3),
                    "cpu": float(m.group(4)),
                    "cmd": m.group(6).strip(),
                }
            )
    facts = {}
    users = [r for r in rows if not r["cmd"].startswith("[")]
    facts["hog_pid"] = str(max(users, key=lambda r: r["cpu"])["pid"])
    zombies = [r for r in rows if r["stat"].startswith("Z")]
    if zombies:
        z = zombies[0]
        facts["zombie_pid"] = str(z["pid"])
        facts["zombie_parent"] = str(z["ppid"])
    m = re.search(r"Out of memory: Killed process (\d+)", dmesg_text)
    if m:
        facts["oom_victim"] = m.group(1)
    def script_of(cmd: str) -> str:
        tokens = cmd.split()
        return tokens[1] if len(tokens) > 1 else ""

    workers = [r for r in rows if script_of(r["cmd"]).endswith("/worker.py")]
    facts["worker_count"] = str(len(workers))
    if workers:
        facts["worker_parent"] = str(workers[0]["ppid"])
    return facts


def main() -> int:
    with open("ps-snapshot.txt", encoding="utf-8") as fh:
        ps_text = fh.read()
    with open("dmesg-oom.txt", encoding="utf-8") as fh:
        dmesg_text = fh.read()
    derived = derive(ps_text, dmesg_text)
    for key, expected in EXPECTED.items():
        if derived.get(key) != expected:
            print(f"FAIL: internal check - evidence derives {key}={derived.get(key)!r}, "
                  f"gate expected {expected!r} (gate/scenario drift)")
            return 1
    try:
        with open("report/proc-report.md", encoding="utf-8") as fh:
            report = fh.read()
    except OSError:
        print("FAIL: report/proc-report.md is missing")
        return 1
    missing = []
    for key, value in EXPECTED.items():
        if not re.search(rf"^{re.escape(key)} = {re.escape(value)}\s*$", report, re.M):
            missing.append(f"{key} = {value}")
    if missing:
        print("FAIL: report lines missing or wrong: " + "; ".join(missing))
        return 1
    print("PASS: all six facts present and consistent with the evidence")
    return 0


if __name__ == "__main__":
    sys.exit(main())
