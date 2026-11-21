#!/usr/bin/env python3
"""Acceptance gate: staging/app.json must equal prod/app.json plus exactly the
allowed overrides from docs/allowed-overrides.json - nothing more, nothing less."""
import json
import sys


def load(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def set_path(doc, dotted, value):
    keys = dotted.split(".")
    node = doc
    for key in keys[:-1]:
        node = node[key]
    node[keys[-1]] = value


def drop_path(doc, dotted):
    keys = dotted.split(".")
    node = doc
    for key in keys[:-1]:
        node = node[key]
    del node[keys[-1]]


def diff_paths(a, b, prefix=""):
    """Dotted paths where the JSON docs differ (structural)."""
    out = set()
    if isinstance(a, dict) and isinstance(b, dict):
        for key in sorted(set(a) | set(b)):
            out |= diff_paths(a.get(key, "<<absent>>"), b.get(key, "<<absent>>"),
                              f"{prefix}.{key}" if prefix else key)
    elif a != b:
        out.add(prefix or "<<root>>")
    return out


def main() -> int:
    prod = load("envs/prod/app.json")
    staging = load("envs/staging/app.json")
    rules = load("docs/allowed-overrides.json")
    expected = json.loads(json.dumps(prod))  # deep copy
    for override in rules.get("overrides", []):
        drop_path(expected, override["path"])
        set_path(expected, override["path"], override["value"])
    problems = []
    for path in sorted(diff_paths(expected, staging)):
        problems.append(f"staging {path} differs from prod+overrides")
    for path in sorted(diff_paths(staging, expected)):
        problems.append(f"staging has extra/wrong value at {path}")
    if problems:
        print("FAIL: " + "; ".join(problems))
        return 1
    print("PASS: staging equals prod plus exactly the allowed overrides")
    return 0


if __name__ == "__main__":
    sys.exit(main())
