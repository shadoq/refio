#!/usr/bin/env python3
"""Acceptance gate: report/extracted.txt must contain exactly the reassembled payload.

The expected plaintext is not stored here - only its sha256 digest - so the gate can be
shipped in the fixture without giving the answer away.
"""
import hashlib
import sys

EXPECTED_SHA256 = "76b277a55bdadfd988eb81414589cdf313bb9a8745bf5300e9d208c282573d2a"


def main() -> int:
    try:
        with open("report/extracted.txt", "rb") as fh:
            data = fh.read()
    except OSError:
        print("FAIL: report/extracted.txt is missing")
        return 1
    stripped = data.strip()
    if not stripped:
        print("FAIL: report/extracted.txt is empty")
        return 1
    digest = hashlib.sha256(stripped).hexdigest()
    if digest != EXPECTED_SHA256:
        print(f"FAIL: sha256 {digest} != expected {EXPECTED_SHA256}")
        print("     (wrong chunk order, noise included, or wrong decoding)")
        return 1
    print("PASS: extracted payload matches the expected digest")
    return 0


if __name__ == "__main__":
    sys.exit(main())
