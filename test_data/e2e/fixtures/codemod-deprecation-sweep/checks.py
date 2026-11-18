"""Acceptance checks for the deprecation sweep. Run: python checks.py"""
import ast
import importlib
import os
import sys
import warnings

ROOT = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, ROOT)
failures = []


def fail(msg):
    failures.append(msg)
    print("FAIL: " + msg)


names = sorted(f[:-3] for f in os.listdir(os.path.join(ROOT, "reports")) if f.startswith("report_") and f.endswith(".py"))
if len(names) != 30:
    fail(f"expected 30 report modules, found {len(names)}")

for nm in names:
    path = os.path.join(ROOT, "reports", nm + ".py")
    with open(path, encoding="utf-8") as fh:
        tree = ast.parse(fh.read())
    uses_new = False
    for node in ast.walk(tree):
        if isinstance(node, ast.Name) and node.id == "fmt_cell":
            fail(f"{nm}: still references fmt_cell")
        if isinstance(node, ast.Attribute) and node.attr == "fmt_cell":
            fail(f"{nm}: still references .fmt_cell")
        if isinstance(node, ast.ImportFrom) and node.module == "textkit.legacy":
            fail(f"{nm}: still imports textkit.legacy")
        if isinstance(node, ast.Import) and any(a.name == "textkit.legacy" for a in node.names):
            fail(f"{nm}: still imports textkit.legacy")
        if isinstance(node, ast.ImportFrom) and node.module == "textkit.render":
            uses_new = True
        if isinstance(node, ast.Import) and any(a.name == "textkit.render" for a in node.names):
            uses_new = True
    if not uses_new:
        fail(f"{nm}: does not use textkit.render")
    with warnings.catch_warnings():
        warnings.simplefilter("error", DeprecationWarning)
        try:
            mod = importlib.import_module("reports." + nm)
            got = mod.render()
        except Exception as exc:  # noqa
            fail(f"{nm}: render() raised {type(exc).__name__}: {exc}")
            continue
    with open(os.path.join(ROOT, "expected", nm + ".txt"), encoding="utf-8") as fh:
        want = fh.read()
    if got != want:
        fail(f"{nm}: output differs from expected")

print(f"checked {len(names)} modules, {len(failures)} problem(s)")
sys.exit(1 if failures else 0)
