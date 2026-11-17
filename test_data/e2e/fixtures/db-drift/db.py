#!/usr/bin/env python3
"""Migration runner: python3 db.py <dbfile> migrate"""
import glob
import os
import sqlite3
import sys

MIGRATIONS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "migrations")


def main() -> int:
    if len(sys.argv) != 3 or sys.argv[2] != "migrate":
        print("usage: db.py <dbfile> migrate", file=sys.stderr)
        return 2
    con = sqlite3.connect(sys.argv[1])
    con.execute(
        "CREATE TABLE IF NOT EXISTS schema_migrations ("
        "name TEXT PRIMARY KEY, applied_at TEXT DEFAULT CURRENT_TIMESTAMP)"
    )
    applied = {row[0] for row in con.execute("SELECT name FROM schema_migrations")}
    for path in sorted(glob.glob(os.path.join(MIGRATIONS_DIR, "*.sql"))):
        name = os.path.basename(path)
        if name in applied:
            continue
        with open(path, encoding="utf-8") as fh:
            sql = fh.read()
        try:
            con.executescript(sql)
        except sqlite3.Error as exc:
            print(f"migration {name} failed: {exc}", file=sys.stderr)
            return 1
        con.execute("INSERT INTO schema_migrations (name) VALUES (?)", (name,))
        con.commit()
        print(f"applied {name}")
    con.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
