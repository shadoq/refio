#!/usr/bin/env python3
"""Acceptance gate: validate etc/logrotate.conf field by field against the policy."""
import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))

REQUIRED = [
    (r"^\s*weekly\s*$", "weekly frequency"),
    (r"^\s*rotate\s+4\s*$", "rotate 4 retention"),
    (r"^\s*compress\s*$", "compress"),
    (r"^\s*create\s+0640\s+app\s+app\s*$", "create 0640 app app"),
    (r"^\s*su\s+app\s+app\s*$", "su app app"),
    (r"^\s*dateext\s*$", "dateext"),
    (r"^\s*missingok\s*$", "missingok"),
    (r"/usr/local/bin/app-reload", "postrotate uses app-reload"),
    (r"^/var/log/app/\*\.log\s*\{", "the /var/log/app/*.log stanza"),
]

FORBIDDEN = [
    (r"^\s*daily\s*$", "daily (old frequency)"),
    (r"^\s*create\s+0644\s+root\s+root\s*$", "create 0644 root root"),
    (r"/bin/kill", "raw kill in postrotate"),
]


def main() -> int:
    path = os.path.join(ROOT, "etc", "logrotate.conf")
    try:
        with open(path, encoding="utf-8") as fh:
            conf = fh.read()
    except OSError:
        print("FAIL: etc/logrotate.conf is missing")
        return 1

    problems = []
    for pattern, what in REQUIRED:
        if not re.search(pattern, conf, re.M):
            problems.append(f"missing: {what}")
    for pattern, what in FORBIDDEN:
        if re.search(pattern, conf, re.M):
            problems.append(f"still present: {what}")

    # postrotate block sanity: the reload command sits between postrotate and endscript
    m = re.search(r"postrotate(.*?)endscript", conf, re.S)
    if not m:
        problems.append("no postrotate/endscript block")
    elif "/usr/local/bin/app-reload" not in m.group(1):
        problems.append("app-reload is not inside the postrotate block")

    if problems:
        print("FAIL: " + "; ".join(problems))
        return 1
    print("PASS: logrotate.conf implements the retention policy exactly")
    return 0


if __name__ == "__main__":
    sys.exit(main())
