"""Runs every python example of README.md and compares its output. Run: python checks.py"""
import contextlib
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, ROOT)
REQUIRED = ["add", "most_common", "total", "merge", "ratio", "parse_counts", "len(", " in "]
REMOVED = ["from_iterable", ".update(", ".top(", " digits=", ".has("]


def main():
    text = open(os.path.join(ROOT, "README.md"), encoding="utf-8").read()
    blocks = list(re.finditer(r"```python\n(.*?)```\n\s*```text\n(.*?)```", text, re.S))
    all_python = text.count("```python")
    problems = []
    if all_python != len(blocks) or len(blocks) < 7:
        problems.append(f"every python example needs a text output block after it, and at least 7 are required (found {len(blocks)} of {all_python})")
    code_all = "\n".join(b.group(1) for b in blocks)
    for name in REQUIRED:
        if name not in code_all:
            problems.append(f"no example uses {name!r} any more")
    for old in REMOVED:
        if old in text:
            problems.append(f"README still mentions the old API {old!r}")
    for n, b in enumerate(blocks, 1):
        buf = io.StringIO()
        try:
            with contextlib.redirect_stdout(buf):
                exec(compile(b.group(1), f"README block {n}", "exec"), {})
        except Exception as exc:
            problems.append(f"block {n} raised {type(exc).__name__}: {exc}")
            continue
        if buf.getvalue().rstrip() != b.group(2).rstrip():
            problems.append(f"block {n} prints {buf.getvalue().rstrip()!r} but the README says {b.group(2).rstrip()!r}")
    for p in problems:
        print("FAIL: " + p)
    print(f"{len(blocks)} example(s), {len(problems)} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
