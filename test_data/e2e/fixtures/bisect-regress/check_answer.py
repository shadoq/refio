#!/usr/bin/env python3
"""Acceptance gate: report/bad-commit.txt must name the first commit where bench fails.

The gate materialises the packed history (history.git.bundle) if .git is not restored
yet, then re-derives the answer itself - for every commit in history it extracts
src/index.py with `git show` (no working-tree disturbance) and runs bench.py against it -
then matches the report's `commit=` line against the derived sha and requires a non-empty
`reason=` line.
"""
import os
import re
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.abspath(__file__))


def run(cmd, check=True):
    return subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, check=check)


def ensure_history():
    """Restore the packed history if the working tree has no .git yet (idempotent)."""
    if os.path.isdir(os.path.join(ROOT, ".git")):
        return
    run(["git", "init", "-q"])
    run(["git", "fetch", "-q", "history.git.bundle", "main"])
    run(["git", "reset", "-q", "--mixed", "FETCH_HEAD"])


def git(*args):
    return subprocess.run(
        ["git", *args], cwd=ROOT, capture_output=True, text=True, check=True
    ).stdout


def bench_ok_for(content: str) -> bool:
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "index.py")
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(content)
        env = dict(os.environ, BENCH_INDEX_PATH=path)
        proc = subprocess.run(
            [sys.executable, "bench.py"], cwd=ROOT, env=env,
            capture_output=True, text=True, timeout=120,
        )
        return proc.returncode == 0


def first_bad_commit():
    shas = git("log", "--reverse", "--format=%H").split()
    for sha in shas:
        if not bench_ok_for(git("show", f"{sha}:src/index.py")):
            return sha
    return None


def main() -> int:
    ensure_history()
    bad = first_bad_commit()
    if bad is None:
        print("FAIL: gate could not derive a bad commit (scenario drift)")
        return 1

    try:
        with open(os.path.join(ROOT, "report", "bad-commit.txt"), encoding="utf-8") as fh:
            report = fh.read()
    except OSError:
        print("FAIL: report/bad-commit.txt is missing")
        return 1

    m = re.search(r"^commit\s*=\s*([0-9a-f]{7,40})\s*$", report, re.M)
    if not m:
        print("FAIL: report has no 'commit=<sha>' line")
        return 1
    claimed = m.group(1)
    if not bad.startswith(claimed) and not claimed.startswith(bad):
        print(f"FAIL: report names {claimed}, but the first bad commit is {bad[:12]} "
              f"({git('log', '-1', '--format=%s', bad).strip()})")
        return 1

    if not re.search(r"^reason\s*=\s*\S.+$", report, re.M):
        print("FAIL: report has no 'reason=<one line>' line")
        return 1

    print(f"PASS: first bad commit {bad[:12]} identified with a reason")
    return 0


if __name__ == "__main__":
    sys.exit(main())
