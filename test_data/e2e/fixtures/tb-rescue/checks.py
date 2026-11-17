#!/usr/bin/env python3
"""Acceptance gate: restored/app.conf must be the byte-exact pristine config from
the rescue archive - verified through its sha256 digest (the plaintext is not
stored here)."""
import hashlib
import sys

EXPECTED_SHA256 = "ccf46318c424d205b451914968a43d4f7f1948214ed7f4d67d963e874a72ec26"


def main() -> int:
    try:
        with open("restored/app.conf", "rb") as fh:
            data = fh.read()
    except OSError:
        print("FAIL: restored/app.conf is missing")
        return 1
    stripped = data.strip()
    if not stripped:
        print("FAIL: restored/app.conf is empty")
        return 1
    digest = hashlib.sha256(stripped).hexdigest()
    if digest != EXPECTED_SHA256:
        print(f"FAIL: sha256 {digest} != expected {EXPECTED_SHA256}")
        print("     (wrong layer decoded, truncated payload, or the drifted file copied)")
        return 1
    print("PASS: the pristine configuration was recovered byte-exactly")
    return 0


if __name__ == "__main__":
    sys.exit(main())
