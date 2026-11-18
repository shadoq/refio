"""Structure check for the refactoring task, then the unit tests.

Run from the project root: python checks/check_refactor.py
"""
import ast
import hashlib
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASELINE = json.loads((Path(__file__).resolve().parent / "baseline.json").read_text(encoding="utf-8"))
MODULE = ROOT / BASELINE["module"]
CLASS_NAME = BASELINE["class"]
TARGETS = BASELINE["targets"]
MAX_TARGET_LINES = BASELINE["max_target_lines"]
MIN_NEW_HELPERS = BASELINE["min_new_helpers"]


def digest(node):
    return hashlib.sha1(ast.dump(node).encode()).hexdigest()


def fail(message):
    print("STRUCTURE CHECK FAILED: " + message)
    sys.exit(1)


tree = ast.parse(MODULE.read_text(encoding="utf-8"))
cls = next((n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == CLASS_NAME), None)
if cls is None:
    fail("class %s is missing" % CLASS_NAME)

rest = "".join(ast.dump(n) for n in tree.body if n is not cls)
if hashlib.sha1(rest.encode()).hexdigest() != BASELINE["module_rest"]:
    fail("module level code outside the class was changed")

seen = {}
for item in cls.body:
    if isinstance(item, ast.FunctionDef):
        seen[item.name] = item
    else:
        key = "<stmt>" + ast.dump(item)[:40]
        if key not in BASELINE["methods"] or digest(item) != BASELINE["methods"][key]["body"]:
            fail("class level statement was changed")

for name, info in BASELINE["methods"].items():
    if name.startswith("<stmt>"):
        continue
    if name not in seen:
        fail("method %s is missing" % name)
    node = seen[name]
    if digest(node.args) != info["args"]:
        fail("signature of %s was changed" % name)
    if name in TARGETS:
        length = node.end_lineno - node.lineno + 1
        if length > MAX_TARGET_LINES:
            fail("%s is still %d lines long, expected at most %d" % (name, length, MAX_TARGET_LINES))
    elif digest(node) != info["body"]:
        fail("method %s must stay untouched but its code changed" % name)

new_helpers = [n for n in seen if n not in BASELINE["methods"]]
public = [n for n in new_helpers if not n.startswith("_")]
if public:
    fail("new helpers must be private (leading underscore): %s" % ", ".join(public))
if len(new_helpers) < MIN_NEW_HELPERS:
    fail("expected at least %d new private helper methods, found %d" % (MIN_NEW_HELPERS, len(new_helpers)))

result = subprocess.run([sys.executable, "-m", "unittest", BASELINE["tests"]], cwd=str(ROOT))
sys.exit(result.returncode)
