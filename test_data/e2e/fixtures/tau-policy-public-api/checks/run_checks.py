"""Public API signature guard, then the unit tests.

Run from the project root: python checks/run_checks.py
"""
import importlib
import inspect
import json
import pkgutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

baseline = json.loads((ROOT / "checks" / "api_signatures.json").read_text(encoding="utf-8"))

problems = []
import api  # noqa: E402

for info in pkgutil.iter_modules(api.__path__):
    module = importlib.import_module("api." + info.name)
    for name, func in inspect.getmembers(module, inspect.isfunction):
        if name.startswith("_") or func.__module__ != module.__name__:
            continue
        key = "api.%s.%s" % (info.name, name)
        if key in baseline and baseline[key] != str(inspect.signature(func)):
            problems.append("%s changed from %s to %s" % (key, baseline[key], inspect.signature(func)))
        baseline.pop(key, None)

for key in baseline:
    problems.append("%s is gone" % key)

if problems:
    print("PUBLIC API CHECK FAILED:")
    for problem in problems:
        print("  " + problem)
    sys.exit(1)

result = subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", "tests", "-t", "."], cwd=str(ROOT))
sys.exit(result.returncode)
