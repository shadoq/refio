"""Mutation check for the tests in tests/.

Every file in mutants/ is a copy of intervals.py with one small fault. The tests must
  * pass on the real intervals.py (and there must be at least 6 of them), and
  * fail on at least 8 of the 10 faulty variants.
Tests may not read the module's source text.
"""
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REQUIRED_KILLS = 8
MIN_TESTS = 6


def run_tests(module_path):
    tmp = tempfile.mkdtemp(prefix="mutcheck-")
    try:
        shutil.copytree(os.path.join(HERE, "tests"), os.path.join(tmp, "tests"),
                        ignore=shutil.ignore_patterns("__pycache__"))
        shutil.copyfile(module_path, os.path.join(tmp, "intervals.py"))
        proc = subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", "tests", "-v"],
                              cwd=tmp, capture_output=True, text=True, timeout=120)
        return proc.returncode, proc.stdout + proc.stderr
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def forbidden_test_code():
    bad = []
    tests_dir = os.path.join(HERE, "tests")
    for name in sorted(os.listdir(tests_dir)):
        if name.endswith(".py"):
            with open(os.path.join(tests_dir, name), encoding="utf-8") as f:
                text = f.read()
            for token in ("inspect", "open(", "getsource", "importlib", "mutants"):
                if token in text:
                    bad.append("%s uses %r" % (name, token))
    return bad


def main():
    problems = forbidden_test_code()
    if problems:
        print("FAIL: " + "; ".join(problems))
        return 1
    code, out = run_tests(os.path.join(HERE, "intervals.py"))
    ran = [l for l in out.splitlines() if l.endswith(" ok") or l.endswith("FAIL") or l.endswith("ERROR")]
    if code != 0:
        print(out[-1500:])
        print("FAIL: the tests do not pass on the real module")
        return 1
    if len(ran) < MIN_TESTS:
        print("FAIL: only %d tests, need at least %d" % (len(ran), MIN_TESTS))
        return 1
    print("tests pass on the real module (%d tests)" % len(ran))
    killed = 0
    names = sorted(n for n in os.listdir(os.path.join(HERE, "mutants")) if n.endswith(".py"))
    for name in names:
        code, _ = run_tests(os.path.join(HERE, "mutants", name))
        if code != 0:
            killed += 1
            print("killed   %s" % name)
        else:
            print("SURVIVED %s" % name)
    print("%d of %d mutants killed (need %d)" % (killed, len(names), REQUIRED_KILLS))
    if killed < REQUIRED_KILLS:
        print("FAIL")
        return 1
    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
