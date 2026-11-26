#!/usr/bin/env python3
"""Offline rotation test: no network, no live server.

Verifies that every old secret value is gone from the whole tree (this file excepted),
that each of the five locations carries a new value, that the app module still boots
(imports), and that report/rotation.txt carries "location: digest" lines where digest is
the first 12 hex chars of sha256 of the NEW value actually found in that location.
"""
import hashlib
import importlib.util
import json
import os
import sqlite3
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

# The five logical secrets and where they live.
LOCATIONS = {
    "app.py:SESSION_PEPPER": "server/app.py",
    "app.py:ADMIN_API_KEY": "server/app.py",
    "seeds.json:API_TOKEN_SEED": "config/seeds.json",
    "seeds.json:WEBHOOK_HMAC_KEY": "config/seeds.json",
    "seed.sqlite:legacy-backend-token": "db/seed.sqlite",
}

# Digests of the retired values (never stored in plain text here).
RETIRED = {
    "app.py:SESSION_PEPPER": "35e510b90a886fc204d1d3588d1f9b57fd2c07f159e9d6a3f6bcfa9f87787e66",
    "app.py:ADMIN_API_KEY": "b3320c7cd75b2afb240e12f23ca60189e5d0ec6d265292b5c0652466cbf66a9b",
    "seeds.json:API_TOKEN_SEED": "0b73397fe045a812529d8cf1f978ce64c58f83b58360fdd08ab6e75d655a68e9",
    "seeds.json:WEBHOOK_HMAC_KEY": "8c80b62ac865f5ec4936488343d2d0f92d665ef00c6ed6bbb5f1b36de05ca4d8",
    "seed.sqlite:legacy-backend-token": "e297143706d02fd0f66bc34bb19082e7b8018cb9518b35444f5aa456ab59eb55",
}


def sha256(s):
    return hashlib.sha256(s.encode("utf-8")).hexdigest()


def load_app():
    spec = importlib.util.spec_from_file_location("tokengate_app", os.path.join(ROOT, "server/app.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def current_values():
    mod = load_app()
    with open(os.path.join(ROOT, "config/seeds.json"), encoding="utf-8") as fh:
        seeds = json.load(fh)
    conn = sqlite3.connect(os.path.join(ROOT, "db/seed.sqlite"))
    try:
        row = conn.execute("select value from tokens where name = 'legacy-backend-token'").fetchone()
    finally:
        conn.close()
    return {
        "app.py:SESSION_PEPPER": mod.SESSION_PEPPER,
        "app.py:ADMIN_API_KEY": mod.ADMIN_API_KEY,
        "seeds.json:API_TOKEN_SEED": seeds["API_TOKEN_SEED"],
        "seeds.json:WEBHOOK_HMAC_KEY": seeds["WEBHOOK_HMAC_KEY"],
        "seed.sqlite:legacy-backend-token": row[0] if row else None,
    }


def main():
    failures = []

    # 1. The app module must still import (boot) with the current secrets.
    try:
        values = current_values()
    except Exception as exc:
        print("FAIL")
        print(" - the app no longer boots with the current secrets: " + repr(exc))
        return 1

    # 2. Every location carries a new, non-empty value, distinct from its retired one
    #    (compared by digest) and distinct from the other new values.
    seen = set()
    for label, value in values.items():
        if not value or not str(value).strip():
            failures.append(label + " is empty")
            continue
        if sha256(str(value)) == RETIRED[label]:
            failures.append(label + " was not rotated (retired value still in place)")
        if str(value) in seen:
            failures.append(label + " reuses another secret's value")
        seen.add(str(value))

    # 3. Full-tree scan: no retired value may appear anywhere (this test suite excepted).
    retired_values = [v for v in (
        "pepper-old-9f3a1c7d42e8", "ak-old-7742bde91c05", "seed-old-2b8e40af13d7",
        "whk-old-58c1ee29a46b", "tok-old-31d9c6e804f2")]
    for dirpath, dirnames, filenames in os.walk(ROOT):
        if os.path.basename(dirpath) == "tests":
            dirnames[:] = []
            continue
        for name in filenames:
            path = os.path.join(dirpath, name)
            try:
                with open(path, "rb") as fh:
                    blob = fh.read()
            except OSError:
                continue
            for val in retired_values:
                if val.encode("utf-8") in blob:
                    failures.append("retired value still present in " + os.path.relpath(path, ROOT))

    # 4. report/rotation.txt: five "location: digest" lines matching the NEW values.
    report_path = os.path.join(ROOT, "report/rotation.txt")
    if not os.path.isfile(report_path):
        failures.append("report/rotation.txt is missing")
    else:
        with open(report_path, encoding="utf-8") as fh:
            lines = {}
            for line in fh:
                line = line.strip()
                if not line or ":" not in line:
                    continue
                loc, digest = line.rsplit(":", 1)
                lines[loc.strip()] = digest.strip().lower()
        if sorted(lines) != sorted(LOCATIONS):
            failures.append("report/rotation.txt does not list exactly the five locations")
        for label, value in values.items():
            want = sha256(str(value))[:12]
            got = lines.get(label)
            if got is None:
                failures.append("report/rotation.txt is missing the " + label + " line")
            elif got != want:
                failures.append("report/rotation.txt digest for " + label + " does not match the new value")

    if failures:
        print("FAIL")
        for f in failures:
            print(" - " + f)
        return 1
    print("PASS: all 5 secrets rotated, retired values absent from the tree, app boots, report digests match")
    return 0


if __name__ == "__main__":
    sys.exit(main())
