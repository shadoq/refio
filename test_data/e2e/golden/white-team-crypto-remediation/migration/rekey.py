#!/usr/bin/env python3
"""One-off runner for the vault rekeying.

Reads the temporary rollout passwords, calls crypto.reencrypt_all on the live data files and
prints how many records were migrated. Run from the project root:

    python3 migration/rekey.py
"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from vault import crypto  # noqa: E402


def main():
    with open(os.path.join(ROOT, "migration", "rollout.json"), encoding="utf-8") as fh:
        passwords = json.load(fh)["passwords"]
    migrated = crypto.reencrypt_all(
        os.path.join(ROOT, "vault", "vault.db"),
        os.path.join(ROOT, "vault", "users.db"),
        passwords,
    )
    print("migrated %d record(s)" % migrated)


if __name__ == "__main__":
    main()
