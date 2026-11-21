"""Runs main.py and checks its output. Run: python checks.py"""
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
EXPECTED = [
    "route legs: [5.0, 12.0]",
    "route length: 17.0",
    "hot spot: (4.0, 2.0)",
    "trail length: 12.0",
]


def main():
    run = subprocess.run([sys.executable, "-B", "main.py"], cwd=ROOT, capture_output=True, text=True, timeout=60)
    lines = run.stdout.strip().splitlines()
    if run.returncode != 0:
        print(run.stderr.strip().splitlines()[-1] if run.stderr.strip() else "main.py failed")
        print("FAIL: main.py exited with", run.returncode)
        return 1
    if lines != EXPECTED:
        print("FAIL: unexpected output:", lines)
        return 1
    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
