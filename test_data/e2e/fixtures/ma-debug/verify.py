#!/usr/bin/env python3
"""Acceptance gate for the debug war room: the agent's regression test must FAIL
on the pristine buggy source (held compressed inside this gate) and PASS on the
current tree; the pre-existing suite must pass unchanged; reports are checked."""
import base64
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
import zlib

ROOT = os.path.dirname(os.path.abspath(__file__))
PRISTINE_BLOB = "eNp1Uj1PwzAQ3fMrnjIlghrKiGBBgg0mNoQqJ742Fo4d2RcQ/56z27SAwIN9Pr/37st1XU96R1H7NzWzdQkrpFE7h4HcRDFhTmTQfYIHggl9wmQnctaTquu6suMUIqPXjrzRcbkbzcR2JOgEw1VVGdqim5PQUtoY/Zk2HfEHkW8S68jnEHp7XUGWyD7NY0cRYYuPITg6MpGZaB6DXz1E28L6kpb1vRPEO0Hq2BFeTpqvqiqid4EHdGH2JkFHOiaosoEmxJ+ebJzn4IlQ6phEEDbB7nyIZFpVVJ8leB88R91zTib352JxqNFkhp55CNGyZklQLRWW024FYL1k63taGmH4mMGhIXmVR9zuzwJo2j80pOL/FORJ+LL/Ymf/zV73BI7Ec/S4rL5dsnj+HRnZqjKJM6wPsx2l6GHj9UhNMU+zvPc7Z9OwRyAjsJV2r1frK7U04hBh+UXqpPZSzNfqCxms22M="
EXISTING_TEST_SHA = "903087734e5524f39552c1f97c5c8083f1ba3e0e8f7803075c1514ec34108eef"


def fail(msg):
    print("FAIL: " + msg)
    return 1


def run_suite(src_dir, test_rel):
    env = dict(os.environ)
    env["PYTHONPATH"] = src_dir
    return subprocess.run(
        [sys.executable, "-m", "unittest", "-v", test_rel],
        cwd=src_dir, capture_output=True, text=True, env=env).returncode


def main():
    # reports
    try:
        repro = open(os.path.join(ROOT, "report/repro.md"), encoding="utf-8").read()
        review = open(os.path.join(ROOT, "report/review.md"), encoding="utf-8").read()
    except OSError as e:
        return fail("missing report: " + str(e))
    if "failing_input = " not in repro:
        return fail("report/repro.md has no `failing_input = <expr>` lines")
    if "verdict = approved" not in review:
        return fail("report/review.md has no `verdict = approved` line")
    if "root_cause = " not in review:
        return fail("report/review.md has no `root_cause = ` line")
    if "business_days_between" not in review:
        return fail("report/review.md does not name the faulty function")

    # the agent's regression test exists
    test_path = os.path.join(ROOT, "tests/test_regression.py")
    if not os.path.isfile(test_path):
        return fail("tests/test_regression.py is missing")

    # pre-existing tests unchanged
    cur = open(os.path.join(ROOT, "tests/test_utils.py"), "rb").read()
    if hashlib.sha256(cur).hexdigest() != EXISTING_TEST_SHA:
        return fail("tests/test_utils.py was modified (pre-existing tests must stay unchanged)")

    # 1. the regression test must FAIL on the pristine buggy source
    tmp = tempfile.mkdtemp(prefix="ma-debug-pristine-")
    try:
        pkg = os.path.join(tmp, "pagerank")
        os.makedirs(pkg)
        open(os.path.join(pkg, "__init__.py"), "w").write("")
        open(os.path.join(pkg, "utils.py"), "w").write(
            zlib.decompress(base64.b64decode(PRISTINE_BLOB)).decode())
        shutil.copy(test_path, os.path.join(tmp, "test_regression.py"))
        rc = run_suite(tmp, "test_regression.py")
        if rc == 0:
            return fail("tests/test_regression.py passes on the pristine buggy source "
                        "(it does not actually pin the bug)")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    # 2. the regression test must PASS on the current tree
    rc = run_suite(ROOT, "tests/test_regression.py")
    if rc != 0:
        return fail("tests/test_regression.py fails on the current tree (bug not fixed)")

    # 3. the pre-existing suite must pass on the current tree
    rc = run_suite(ROOT, "tests/test_utils.py")
    if rc != 0:
        return fail("the pre-existing suite tests/test_utils.py fails")

    print("PASS: regression test pins the bug, fix holds, existing suite green, reports present")
    return 0


if __name__ == "__main__":
    sys.exit(main())
