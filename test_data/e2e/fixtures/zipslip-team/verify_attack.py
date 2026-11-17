#!/usr/bin/env python3
"""Acceptance gate: the malicious archive must no longer escape the destination, and the
clean archive must still extract correctly.

Replays samples/evil.zip into a scratch destination: the canary file outside the
destination must NOT appear, and the extraction must not crash. Then extracts
samples/legit.zip and checks every file landed with the right content.
"""
import os
import shutil
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(ROOT, "src"))
from extractor import extract_archive  # noqa: E402

SCRATCH = os.path.join(ROOT, "gate-scratch")


def main() -> int:
    shutil.rmtree(SCRATCH, ignore_errors=True)
    os.makedirs(os.path.join(SCRATCH, "dest"), exist_ok=True)

    # 1. the malicious archive must not write outside the destination
    try:
        extract_archive(os.path.join(ROOT, "samples", "evil.zip"),
                        os.path.join(SCRATCH, "dest"))
    except Exception as exc:  # noqa: BLE001 - crashing on a hostile archive also fails
        print(f"FAIL: extraction of the malicious archive crashed: {exc}")
        return 1
    canary = os.path.join(ROOT, "canary", "pwned.txt")
    if os.path.exists(canary):
        print("FAIL: canary/pwned.txt was written - zip-slip is still possible")
        return 1
    escaped = [p for p in os.listdir(SCRATCH) if p != "dest"] if os.path.isdir(SCRATCH) else []
    if escaped:
        print(f"FAIL: files escaped the destination: {escaped}")
        return 1

    # 2. the clean archive extracts fully and correctly
    clean_dest = os.path.join(SCRATCH, "clean")
    os.makedirs(clean_dest, exist_ok=True)
    extract_archive(os.path.join(ROOT, "samples", "legit.zip"), clean_dest)
    expected = {
        os.path.join("notes", "readme.txt"): "clean archive payload\n",
        "data.csv": "id,label\n1,first\n",
    }
    for rel, content in expected.items():
        path = os.path.join(clean_dest, rel)
        if not os.path.isfile(path):
            print(f"FAIL: clean archive entry {rel!r} did not land")
            return 1
        with open(path, encoding="utf-8") as fh:
            if fh.read() != content:
                print(f"FAIL: clean archive entry {rel!r} has wrong content")
                return 1

    shutil.rmtree(SCRATCH, ignore_errors=True)
    print("PASS: malicious archive contained, clean archive extracts correctly")
    return 0


if __name__ == "__main__":
    sys.exit(main())
