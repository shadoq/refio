#!/usr/bin/env python3
"""Acceptance gate: the repair migration must converge drift, preserve data, and stay clean
on fresh installs and repeated runs.

1. Drifted copy: migrate -> tags table exists with 003's columns; users/items rows intact.
2. Fresh db: migrate -> identical schema (tables and columns).
3. Idempotence: a second migrate run on both is a no-op (exit 0, schema unchanged).
4. History: migrations 001-003 remain recorded exactly once; the snapshot is untouched.
"""
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.abspath(__file__))


def migrate(db_path):
    return subprocess.run(
        [sys.executable, os.path.join(ROOT, "db.py"), db_path, "migrate"],
        cwd=ROOT, capture_output=True, text=True,
    )


def schema(db_path):
    con = sqlite3.connect(db_path)
    try:
        tables = {r[0] for r in con.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")}
        cols = {t: [r[1] for r in con.execute(f"PRAGMA table_info({t})")] for t in tables}
        rows = {}
        for t in ("users", "items"):
            if t in tables:
                rows[t] = sorted(con.execute(f"SELECT * FROM {t}"))
        recorded = [r[0] for r in con.execute("SELECT name FROM schema_migrations ORDER BY name")]
        return {"tables": tables, "cols": cols, "rows": rows, "recorded": recorded}
    finally:
        con.close()


def main() -> int:
    problems = []

    with tempfile.TemporaryDirectory() as tmp:
        drifted = os.path.join(tmp, "drifted.sqlite")
        shutil.copy(os.path.join(ROOT, "db.sqlite.drifted"), drifted)

        r1 = migrate(drifted)
        if r1.returncode != 0:
            print(f"FAIL: migrate on drifted db failed: {r1.stderr.strip()[:300]}")
            return 1
        s_drifted = schema(drifted)

        if "tags" not in s_drifted["tables"]:
            problems.append("drifted db still has no tags table after migrate")
        elif s_drifted["cols"].get("tags") != ["id", "item_id", "tag"]:
            problems.append(f"tags columns are {s_drifted['cols'].get('tags')}, expected 003's")
        if s_drifted["rows"].get("users") != [(1, "alice"), (2, "bob")]:
            problems.append("users rows did not survive the repair")
        if s_drifted["rows"].get("items") != [(1, "alice", "laptop"), (2, "bob", "mug")]:
            problems.append("items rows did not survive the repair")

        fresh = os.path.join(tmp, "fresh.sqlite")
        open(fresh, "w").close()
        r2 = migrate(fresh)
        if r2.returncode != 0:
            print(f"FAIL: migrate on fresh db failed: {r2.stderr.strip()[:300]}")
            return 1
        s_fresh = schema(fresh)

        if s_fresh["cols"].get("tags") != s_drifted["cols"].get("tags"):
            problems.append("fresh and drifted installs end with different tags schema")
        if {t: c for t, c in s_fresh["cols"].items()} != {t: c for t, c in s_drifted["cols"].items()}:
            problems.append("fresh and drifted installs end with different overall schema")

        r3 = migrate(drifted)
        r4 = migrate(fresh)
        if r3.returncode != 0 or r4.returncode != 0:
            problems.append("second migrate run is not a clean no-op")
        elif schema(drifted) != s_drifted or schema(fresh) != s_fresh:
            problems.append("second migrate run changed the schema")

        for name in ("001_init.sql", "002_seed.sql", "003_add_tags.sql"):
            if name not in s_drifted["recorded"]:
                problems.append(f"{name} no longer recorded on the drifted db")

    snapshot = os.path.join(ROOT, "db.sqlite.drifted")
    con = sqlite3.connect(f"file:{snapshot}?mode=ro", uri=True)
    recorded = [r[0] for r in con.execute("SELECT name FROM schema_migrations ORDER BY name")]
    tags = con.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='tags'").fetchall()
    con.close()
    if recorded != ["001_init.sql", "002_seed.sql", "003_add_tags.sql"]:
        problems.append(f"the drifted snapshot's recording changed: {recorded}")
    if tags:
        problems.append("the drifted snapshot itself was migrated (it must stay untouched)")

    if problems:
        print("FAIL: " + "; ".join(problems))
        return 1
    print("PASS: drift converged, data preserved, fresh install clean, re-runs are no-ops")
    return 0


if __name__ == "__main__":
    sys.exit(main())
