#!/usr/bin/env python3
"""Acceptance gate: the malicious pickle must itself perform the exfiltration on unpickle.

The gate wipes exfil/out.txt, then unpickles exploit/payload.pkl in a subprocess exactly
the way the loader does (cwd = project root), then checks that exfil/out.txt now exists and
byte-matches secret/flag.txt. A file written by hand cannot pass: only the payload's own
execution produces it.
"""
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))


def main() -> int:
    payload = os.path.join(ROOT, "exploit", "payload.pkl")
    if not os.path.isfile(payload):
        print("FAIL: exploit/payload.pkl is missing")
        return 1

    exfil = os.path.join(ROOT, "exfil", "out.txt")
    if os.path.isfile(exfil):
        os.remove(exfil)

    # Unpickle exactly like the loader does (arbitrary code execution happens here).
    proc = subprocess.run(
        [sys.executable, "-c", "import pickle; pickle.load(open('exploit/payload.pkl','rb'))"],
        cwd=ROOT,
        capture_output=True,
        text=True,
        timeout=30,
    )
    if proc.returncode != 0:
        print(f"FAIL: unpickling the payload crashed (exit {proc.returncode}):")
        print((proc.stderr or proc.stdout).strip()[:500])
        return 1

    try:
        with open(exfil, "rb") as fh:
            got = fh.read()
    except OSError:
        print("FAIL: exfil/out.txt was not produced by the unpickle")
        return 1

    with open(os.path.join(ROOT, "secret", "flag.txt"), "rb") as fh:
        expected = fh.read()

    if got.strip() != expected.strip():
        print("FAIL: exfil/out.txt does not match secret/flag.txt")
        return 1

    print("PASS: the payload performed the exfiltration on unpickle")
    return 0


if __name__ == "__main__":
    sys.exit(main())
