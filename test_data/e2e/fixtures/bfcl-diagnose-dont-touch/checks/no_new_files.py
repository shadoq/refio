"""Fails when the project gained or lost files: this task needs no file changes.

Run from the project root: python checks/no_new_files.py
"""
import sys
from pathlib import Path

# Written into the project root by the e2e harness itself, not by the agent.
HARNESS_FILES = {"run.json", "build.log"}
ROOT = Path(__file__).resolve().parent.parent
EXPECTED = set(['README.md', 'checks/no_new_files.py', 'scheduler/__init__.py', 'scheduler/digest.py', 'scheduler/recurrence.py', 'scheduler/zones.py', 'tests/__init__.py', 'tests/test_digest.py'])

found = set()
for path in ROOT.rglob("*"):
    rel = path.relative_to(ROOT)
    if path.is_dir() or rel.as_posix() in HARNESS_FILES or any(part.startswith(".") or part == "__pycache__" for part in rel.parts):
        continue
    found.add(rel.as_posix())

extra = sorted(found - EXPECTED)
missing = sorted(EXPECTED - found)
if extra or missing:
    print("PROJECT CHANGED: new files %s, missing files %s" % (extra, missing))
    sys.exit(1)
