"""Measures how much of timecard.py the tests in tests/ execute.

Runs every test under a line tracer (sys.settrace) and passes only when
  * the module is byte-for-byte the one that was handed in,
  * all tests pass and there are at least 8 of them,
  * every test function contains an assertion,
  * at least 90 percent of the executable lines of timecard.py were executed.
"""
import ast
import hashlib
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
TARGET = os.path.join(HERE, "timecard.py")
TARGET_REAL = os.path.normcase(os.path.realpath(TARGET))
EXPECTED_SHA256 = "ea39c39dcffd5abec554f225b03074e75341fffced694787409b48062c2ccf00"
THRESHOLD = 90.0
MIN_TESTS = 8


def module_sha256():
    with open(TARGET, "rb") as f:
        data = f.read().replace(b"\r\n", b"\n")
    return hashlib.sha256(data).hexdigest()


def executable_lines():
    with open(TARGET, encoding="utf-8") as f:
        code = compile(f.read(), TARGET, "exec")
    lines = set()
    stack = [code]
    while stack:
        co = stack.pop()
        for _, _, line in co.co_lines():
            if line is not None:
                lines.add(line)
        stack.extend(c for c in co.co_consts if hasattr(c, "co_lines"))
    return lines


def tests_without_assertions():
    bad = []
    tests_dir = os.path.join(HERE, "tests")
    for name in sorted(os.listdir(tests_dir)):
        if not (name.startswith("test") and name.endswith(".py")):
            continue
        with open(os.path.join(tests_dir, name), encoding="utf-8") as f:
            tree = ast.parse(f.read())
        for node in ast.walk(tree):
            if isinstance(node, ast.FunctionDef) and node.name.startswith("test"):
                has_assert = False
                for sub in ast.walk(node):
                    if isinstance(sub, ast.Assert):
                        has_assert = True
                    elif isinstance(sub, ast.Call) and isinstance(sub.func, ast.Attribute) \
                            and sub.func.attr.startswith("assert"):
                        has_assert = True
                if not has_assert:
                    bad.append("%s::%s" % (name, node.name))
    return bad


def run_traced():
    executed = set()

    def local(frame, event, arg):
        if event == "line":
            executed.add(frame.f_lineno)
        return local

    def global_trace(frame, event, arg):
        if os.path.normcase(os.path.realpath(frame.f_code.co_filename)) == TARGET_REAL:
            executed.add(frame.f_lineno)
            return local
        return None

    for mod in [m for m in sys.modules if m == "timecard" or m.startswith("tests")]:
        del sys.modules[mod]
    sys.path.insert(0, HERE)
    result = None
    sys.settrace(global_trace)
    try:
        suite = unittest.TestLoader().discover(os.path.join(HERE, "tests"), top_level_dir=HERE)
        result = unittest.TextTestRunner(stream=open(os.devnull, "w"), verbosity=0).run(suite)
    finally:
        sys.settrace(None)
    return result, executed


def main():
    problems = []
    if module_sha256() != EXPECTED_SHA256:
        problems.append("timecard.py was modified")
    missing = [n for n in ("tests",) if not os.path.isdir(os.path.join(HERE, n))]
    if missing:
        problems.append("tests directory is missing")
        print("FAIL: " + "; ".join(problems))
        return 1
    result, executed = run_traced()
    if result.testsRun < MIN_TESTS:
        problems.append("only %d tests, need at least %d" % (result.testsRun, MIN_TESTS))
    if not result.wasSuccessful():
        problems.append("%d test failures/errors" % (len(result.failures) + len(result.errors)))
    bad = tests_without_assertions()
    if bad:
        problems.append("tests without assertions: " + ", ".join(bad))
    lines = executable_lines()
    covered = lines & executed
    pct = 100.0 * len(covered) / len(lines)
    print("tests run: %d, line coverage of timecard.py: %.1f%% (%d of %d lines)" % (
        result.testsRun, pct, len(covered), len(lines)))
    missed = sorted(lines - executed)
    if missed:
        print("lines not executed: " + ", ".join(str(n) for n in missed))
    if pct < THRESHOLD:
        problems.append("coverage %.1f%% is below %.0f%%" % (pct, THRESHOLD))
    if problems:
        print("FAIL: " + "; ".join(problems))
        return 1
    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
