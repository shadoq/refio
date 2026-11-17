#!/usr/bin/env python3
"""Case runner for the coin-change exercise: runs solver.py against every
tests/cases/*.in and compares with the matching .out (5 s timeout per case)."""
import pathlib
import subprocess
import sys

TIMEOUT_S = 5


def main() -> int:
    cases_dir = pathlib.Path("tests/cases")
    case_files = sorted(cases_dir.glob("*.in"))
    if not case_files:
        print("FAIL: no cases found under tests/cases")
        return 1
    failures = []
    for case_file in case_files:
        expected = case_file.with_suffix(".out").read_text().strip()
        try:
            proc = subprocess.run(
                [sys.executable, "solver.py"],
                input=case_file.read_text(),
                capture_output=True,
                text=True,
                timeout=TIMEOUT_S,
            )
        except subprocess.TimeoutExpired:
            failures.append(f"{case_file.name}: timed out after {TIMEOUT_S}s")
            continue
        got = proc.stdout.strip()
        if proc.returncode != 0:
            failures.append(f"{case_file.name}: solver exited {proc.returncode}: "
                            f"{proc.stderr.strip()[:160]}")
        elif got != expected:
            failures.append(f"{case_file.name}: expected {expected!r}, got {got!r}")
        else:
            print(f"  ok {case_file.name}")
    if failures:
        print("FAIL: " + "; ".join(failures))
        return 1
    print(f"PASS: all {len(case_files)} cases green")
    return 0


if __name__ == "__main__":
    sys.exit(main())
