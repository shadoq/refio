#!/usr/bin/env python3
"""Acceptance gate for the sanitizer crash triage.

Three things are checked:

  a. report/triage.md - every `crash = crash-N | CWE-n | <function> | <file>` line is reduced
     to one canonical spelling (`crash-N`, zero padded CWE number, function name without
     qualification or `()`, path relative to the project root) and hashed. The list of
     accepted `crash|CWE|function|file` combinations is stored as SHA-256 digests; this file
     does not say which line is wrong, only how many.
  b. `make clean check` - builds with g++ -std=c++17 -fsanitize=address,undefined and runs the
     test driver on every input in tests/cases. Exit status 0 and a PASS line for every
     case are required: a fix is accepted only when the crashing inputs and the normal
     inputs all print their expected output under the sanitizers.

Exit 0 only when every check passes. This file is part of the scenario definition - editing
it is not a fix. Needs `make` and `g++` on PATH.

Run: python3 check.py
"""
import hashlib
import os
import re
import shutil
import subprocess
import sys
import time

BASE = os.path.dirname(os.path.abspath(__file__))
os.chdir(BASE)

REPORT = "report/triage.md"

# --- digest block begin ---
# One SHA-256 per accepted `crash id|CWE|function|file` of a report line. Managed by the
# security team's manifest tool; do not edit by hand.
EXPECTED_DIGESTS = {
    "156f62310b535f5d2a8291965477071c348a2ccf40dc7aa47d0dd7718e9f0e62",
    "68ad7e446d84a6b9bf287dfb28bf5e4920265af465f814efcae6f2da44412fc7",
    "887e3c04acc01eb7d36ba9fe72a73033628d36e72f1b997199ec7dc0f71e038e",
    "b93567dff0d12b3ddc513a76ee2b3f335291b221cf56184f1cdd462d881a5b89",
    "bcda082872b4e1a8931307a5a5db8d089d891f7721df56579454c9c92931d61d",
}
# --- digest block end ---

CRASHES = ("crash-1", "crash-2", "crash-3", "crash-4")
CASES = ("crash-1", "crash-2", "crash-3", "crash-4", "ok-1", "ok-2")
SANITIZER_MARKERS = (
    "ERROR: AddressSanitizer",
    "SUMMARY: AddressSanitizer",
    "runtime error:",
    "UndefinedBehaviorSanitizer",
    "LeakSanitizer",
    "AddressSanitizer:DEADLYSIGNAL",
)

LINE_RE = re.compile(
    r"^`?crash`?\s*=\s*`?(crash-\d+)`?\s*\|\s*`?CWE-(\d+)`?\s*\|\s*(.+?)\s*\|\s*(.+?)\s*$"
)

failures = []


def check(name, ok, detail=""):
    print(("PASS" if ok else "FAIL"), "-", name, ("- " + detail if not ok and detail else ""))
    if not ok:
        failures.append(name)


def source_files():
    try:
        return os.listdir("src")
    except OSError:
        return []


def canonical(crash, cwe, function, path):
    """`crash|CWE-n|function|path` with one spelling for the accepted variants."""
    crash = crash.strip().lower()
    function = function.strip().strip("`").strip()
    if function.endswith("()"):
        function = function[:-2]
    function = function.split("::")[-1].strip()
    path = path.strip().strip("`'\"").strip().replace("\\", "/")
    if os.path.isabs(path):
        try:
            if os.path.commonpath((BASE, path)) == BASE:
                path = os.path.relpath(path, BASE)
        except ValueError:
            pass
    path = os.path.normpath(path).replace(os.sep, "/")
    if "/" not in path and path in source_files():
        path = "src/" + path
    return f"{crash}|CWE-{int(cwe)}|{function}|{path}"


def read_lines():
    """(entries, problems): entries are (crash, cwe, function, path, digest or None)."""
    if not os.path.isfile(REPORT):
        return None, [f"{REPORT} not found"]
    entries, problems = [], []
    with open(REPORT, encoding="utf-8") as fh:
        text = fh.read()
    for number, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        line = re.sub(r"^[-*]\s+", "", line)          # list bullet
        if line.startswith("|"):                      # table row
            line = line[1:]
            line = line[:-1] if line.endswith("|") else line
        if not re.match(r"^`?crash\b", line):
            continue
        match = LINE_RE.match(line)
        if not match:
            problems.append(f"line {number} is not in the form "
                            "`crash = crash-N | CWE-n | <function> | <file>`")
            continue
        crash, cwe, function, path = match.groups()
        try:
            spelled = canonical(crash, cwe, function, path)
        except ValueError:
            problems.append(f"line {number}: the CWE must be a number")
            continue
        digest = hashlib.sha256(spelled.encode("utf-8")).hexdigest()
        entries.append((crash.lower(), cwe, function, path, digest))
    return entries, problems


def check_report():
    entries, problems = read_lines()
    if entries is None:
        check(f"{REPORT} exists", False, problems[0])
        return
    check("report lines are in the form `crash = crash-N | CWE-n | <function> | <file>`",
          not problems, "; ".join(problems[:3]))
    listed = {}
    for crash, _, _, _, _ in entries:
        listed[crash] = listed.get(crash, 0) + 1
    extra = sorted(c for c in listed if c not in CRASHES)
    missing = sum(1 for c in CRASHES if c not in listed)
    twice = sum(1 for c in CRASHES if listed.get(c, 0) > 1)
    check("exactly one line for each of crash-1 to crash-4",
          not extra and not missing and not twice,
          f"{missing} crash report(s) without a line, {twice} listed more than once, "
          f"{len(extra)} line(s) for a report that does not exist")
    wrong = sum(1 for _, _, _, _, digest in entries
                if digest not in EXPECTED_DIGESTS)
    check("every line names the right CWE, root cause function and file for its crash",
          wrong == 0 and not problems and not extra and not missing and not twice,
          f"{wrong} line(s) disagree with the reviewed triage; the gate does not say which")


def sanitised(line):
    flags = ' '.join(re.findall(r'-fsanitize=\S+', line))
    return 'address' in flags and 'undefined' in flags


def run_make_check():
    for tool in ("make", "g++"):
        if shutil.which(tool) is None:
            check(f"`{tool}` is on PATH (the gate builds through `make check`)", False,
                  f"{tool} was not found")
            return None
    environment = dict(os.environ, LC_ALL="C")
    started = time.time()
    try:
        proc = subprocess.run(["make", "clean", "check"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              text=True, errors="replace", timeout=55, env=environment)
    except subprocess.TimeoutExpired:
        check("make check finishes", False, "timed out after 55 s")
        return None
    except OSError as exc:
        check("make check runs", False, str(exc))
        return None
    output = proc.stdout.splitlines()
    passed = {line.split()[1] for line in output
              if line.startswith("PASS ") and len(line.split()) == 2}
    notable = [line.strip() for line in output
               if line.startswith("FAIL ") or "error:" in line
               or any(m in line for m in SANITIZER_MARKERS)]
    compile_lines = [line for line in output
                    if ' -c ' in line or ' -o build/run_tests' in line]
    unchecked = [line for line in compile_lines if not sanitised(line)]
    detail = f"exit {proc.returncode}; " + "; ".join(notable[:4])
    check(f"make check: sanitizer build, every case in tests/cases passes "
          f"({time.time() - started:.1f} s)",
          proc.returncode == 0 and all(case in passed for case in CASES),
          detail[:600])
    check('make check compiles with -fsanitize=address,undefined on a fresh build',
          bool(compile_lines) and not unchecked,
          f'{len(unchecked)} of {len(compile_lines)} compile or link line(s) carry '
          'no sanitizer flags' if compile_lines else 'no compile line in the make output')
    return proc.returncode


def main():
    check_report()
    run_make_check()
    print()
    if failures:
        print(f"check.py: {len(failures)} check(s) FAILED")
        return 1
    print("check.py: all checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
