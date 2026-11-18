"""Local CI pipeline: lint, test, build. Stops at the first failing stage."""
import ast
import importlib
import json
import os
import sys
import unittest

ROOT = os.path.dirname(os.path.abspath(__file__))
PKG = "shipcalc"
MAX_LINE = 79


def modules():
    folder = os.path.join(ROOT, PKG)
    return [n for n in sorted(os.listdir(folder)) if n.endswith(".py")]


def lint():
    problems = []
    for name in modules():
        path = os.path.join(ROOT, PKG, name)
        with open(path, encoding="utf-8") as fh:
            src = fh.read()
        for no, line in enumerate(src.splitlines(), 1):
            if len(line) > MAX_LINE:
                problems.append(f"{name}:{no}: E501 line too long ({len(line)} > {MAX_LINE})")
            if line != line.rstrip():
                problems.append(f"{name}:{no}: W291 trailing whitespace")
            if "\t" in line:
                problems.append(f"{name}:{no}: W191 tab indentation")
        tree = ast.parse(src)
        imported = {}
        used = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                for a in node.names:
                    imported[(a.asname or a.name).split(".")[0]] = node.lineno
            elif isinstance(node, ast.ImportFrom):
                for a in node.names:
                    imported[a.asname or a.name] = node.lineno
            elif isinstance(node, ast.Name):
                used.add(node.id)
            if (isinstance(node, ast.Call) and isinstance(node.func, ast.Name)
                    and node.func.id == "print"):
                problems.append(f"{name}:{node.lineno}: T201 print() in library code")
        if name != "__init__.py":
            for sym, no in sorted(imported.items(), key=lambda kv: kv[1]):
                if sym not in used:
                    problems.append(f"{name}:{no}: F401 '{sym}' imported but unused")
    return problems


def test():
    suite = unittest.defaultTestLoader.discover(os.path.join(ROOT, "tests"), top_level_dir=ROOT)
    result = unittest.TextTestRunner(verbosity=1, stream=sys.stdout).run(suite)
    return [] if result.wasSuccessful() else ["unit tests failed"]


def build():
    problems = []
    sys.path.insert(0, ROOT)
    built = {}
    for name in modules():
        mod_name = PKG if name == "__init__.py" else f"{PKG}.{name[:-3]}"
        mod = importlib.import_module(mod_name)
        exported = getattr(mod, "__all__", None)
        if exported is None:
            problems.append(f"{mod_name}: missing __all__")
            continue
        for sym in exported:
            if not hasattr(mod, sym):
                problems.append(f"{mod_name}: __all__ lists '{sym}' which is not defined")
        built[mod_name] = sorted(exported)
    if not problems:
        os.makedirs(os.path.join(ROOT, "dist"), exist_ok=True)
        with open(os.path.join(ROOT, "dist", "build.json"), "w", encoding="utf-8") as fh:
            json.dump(built, fh, indent=2, sort_keys=True)
    return problems


def main():
    for stage in (lint, test, build):
        print(f"== {stage.__name__}")
        problems = stage()
        for p in problems:
            print("  " + p)
        if problems:
            print(f"CI FAILED at stage: {stage.__name__}")
            return 1
    print("CI OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
