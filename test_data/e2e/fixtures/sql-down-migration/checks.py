"""Runs up, down, up on an in-memory SQLite database and compares schema snapshots.

Run: python checks.py
"""
import glob
import os
import sqlite3
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
MIG = os.path.join(ROOT, "migrations")

SEED = [
    "INSERT INTO customers (id, email, name) VALUES (1, 'ann@example.test', 'Ann'), (2, 'bo@example.test', 'Bo')",
    "INSERT INTO orders (id, customer_id, total_cents) VALUES (10, 1, 500), (11, 1, 250), (12, 2, 900)",
]


def versions():
    ups = sorted(glob.glob(os.path.join(MIG, "*.up.sql")))
    return [os.path.basename(p)[:-len(".up.sql")] for p in ups]


def run(conn, name, direction):
    path = os.path.join(MIG, f"{name}.{direction}.sql")
    if not os.path.isfile(path):
        raise FileNotFoundError(f"missing migrations/{name}.{direction}.sql")
    with open(path, encoding="utf-8") as fh:
        conn.executescript(fh.read())


def snapshot(conn):
    snap = {}
    for typ, name, tbl in conn.execute(
            "SELECT type, name, tbl_name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name"):
        entry = {"type": typ, "tbl": tbl}
        if typ == "table":
            entry["columns"] = [tuple(r[1:]) for r in conn.execute(f"PRAGMA table_info({name})")]
            entry["fks"] = sorted(tuple(r[2:]) for r in conn.execute(f"PRAGMA foreign_key_list({name})"))
            indexes = []
            for r in conn.execute(f"PRAGMA index_list({name})"):
                cols = [c[2] for c in conn.execute(f"PRAGMA index_info({r[1]})")]
                indexes.append((r[2], tuple(cols)))
            entry["indexes"] = sorted(indexes)
            entry["rows"] = conn.execute(f"SELECT * FROM {name} ORDER BY 1").fetchall()
        snap[name] = entry
    return snap


def main():
    names = versions()
    problems = []
    if len(names) != 3:
        print("FAIL: the three up migrations must stay in place")
        return 1
    conn = sqlite3.connect(":memory:")
    conn.execute("PRAGMA foreign_keys = ON")
    snaps = [snapshot(conn)]
    try:
        for i, name in enumerate(names):
            run(conn, name, "up")
            if i == 0:
                for stmt in SEED:
                    conn.execute(stmt)
                conn.commit()
            snaps.append(snapshot(conn))
        full = snaps[-1]
        for i in range(len(names) - 1, -1, -1):
            run(conn, names[i], "down")
            got = snapshot(conn)
            if got != snaps[i]:
                diff = sorted(k for k in set(got) | set(snaps[i]) if got.get(k) != snaps[i].get(k))
                problems.append(f"after down {names[i]} the schema or data differs from the state before its up: {diff}")
                break
        else:
            for i, name in enumerate(names):
                run(conn, name, "up")
                if i == 0:
                    for stmt in SEED:
                        conn.execute(stmt)
                    conn.commit()
            again = snapshot(conn)
            if again != full:
                problems.append("up after down does not give the same result as the first up")
    except Exception as exc:
        problems.append(f"{type(exc).__name__}: {exc}")
    for p in problems:
        print("FAIL: " + p)
    print(f"{len(problems)} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
