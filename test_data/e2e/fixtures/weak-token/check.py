#!/usr/bin/env python3
"""Acceptance gate: forged/admin.txt must hold a valid, unexpired token for exactly 'admin'."""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tokens import verify  # noqa: E402

TEN_YEARS = 10 * 366 * 24 * 3600


def main() -> int:
    try:
        with open("forged/admin.txt", encoding="utf-8") as fh:
            token = fh.read().strip()
    except OSError:
        print("FAIL: forged/admin.txt is missing")
        return 1
    if not token:
        print("FAIL: forged/admin.txt is empty")
        return 1

    claims = verify(token)
    if claims is None:
        print("FAIL: the forged token does not verify (bad signature or expired)")
        return 1
    if claims["user"] != "admin":
        print(f"FAIL: token is for {claims['user']!r}, not 'admin'")
        return 1
    now = time.time()
    if claims["expiry"] <= now:
        print("FAIL: token is already expired")
        return 1
    if claims["expiry"] > now + TEN_YEARS:
        print("FAIL: expiry is implausibly far in the future (bound: 10 years)")
        return 1

    with open("secret/flag.txt", encoding="utf-8") as fh:
        flag = fh.read().strip()
    print(f"PASS: valid admin token (expiry {claims['expiry']}). {flag}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
