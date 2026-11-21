"""Checks reports/timeouts.md against the service configs.

Run from the project root: python checks/check_report.py
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

expected = {}
for path in sorted((ROOT / "config").glob("svc-*.yaml")):
    name = None
    timeout = None
    in_runtime = False
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("name:"):
            name = line.split(":", 1)[1].strip()
        if line.startswith("runtime:"):
            in_runtime = True
            continue
        if in_runtime and re.match(r"^  timeout_ms: *[0-9]+ *$", line):
            timeout = int(line.split(":", 1)[1])
        if line and not line.startswith(" "):
            in_runtime = line.startswith("runtime:")
    expected[name] = timeout

report = ROOT / "reports" / "timeouts.md"
if not report.is_file():
    print("reports/timeouts.md does not exist")
    sys.exit(1)

text = report.read_text(encoding="utf-8")
rows = []
for line in text.splitlines():
    match = re.match(r"^\|\s*([a-z][a-z-]*)\s*\|\s*([0-9]+)\s*\|\s*$", line)
    if match:
        rows.append((match.group(1), int(match.group(2))))

problems = []
if sorted(rows) != sorted(expected.items()):
    problems.append("table rows %r do not match the configs %r" % (rows, sorted(expected.items())))
elif [t for _, t in rows] != sorted((t for _, t in rows), reverse=True):
    problems.append("rows must be sorted by timeout_ms, slowest first: %r" % (rows,))

slowest = max(expected, key=expected.get)
total_line = "Total: %d services, slowest: %s" % (len(expected), slowest)
if total_line not in text.splitlines():
    problems.append("missing final line: " + total_line)

if problems:
    for problem in problems:
        print(problem)
    sys.exit(1)
print("report ok")
