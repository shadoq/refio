#!/usr/bin/env python3
"""Acceptance gate for the retention contract.

Recomputes everything from the database: triggers exist and keep the newest
10000 rows on INSERT (tested by inserting 20000 fresh rows in one
transaction), prune_events() trims to the newest 10000, a fresh connection
insert behaves the same, other tables and their rows are untouched, the
documented indexes still exist, and no orphan/garbage rows appear.
"""
import os
import sqlite3
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
DB = os.path.join(ROOT, "data", "events.db")
CAP = 10000


def fail(msg):
    print("FAIL: " + msg)
    sys.exit(1)


def snap(conn):
    """Fingerprint of everything except the events table."""
    out = {}
    for (name,) in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name != 'events'"):
        rows = conn.execute("SELECT * FROM %s" % name).fetchall()
        out[name] = repr(rows)
    return out


def main():
    if not os.path.isfile(DB):
        fail("data/events.db missing")
    conn = sqlite3.connect(DB)

    tables = {r[0] for r in conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table'")}
    for t in ("sources", "kinds"):
        if t not in tables:
            fail("table %s disappeared" % t)

    idx = {r[0] for r in conn.execute(
        "SELECT name FROM sqlite_master WHERE type='index'")}
    for i in ("idx_events_kind", "idx_events_source"):
        if i not in idx:
            fail("index %s missing (documented queries need it)" % i)

    base = snap(conn)

    # 1. Bulk insert through the plain application path.
    cur = conn.cursor()
    cur.execute("SELECT MAX(id) FROM events")
    max_id = cur.fetchone()[0] or 0
    cur.executemany(
        "INSERT INTO events(id, ts, kind, source, payload) VALUES(?, ?, ?, ?, ?)",
        [(max_id + 1 + i, "2026-11-25T00:00:00", "audit", "gate", "x" * 8)
         for i in range(20000)])
    conn.commit()
    n, lo, hi = conn.execute(
        "SELECT COUNT(*), MIN(id), MAX(id) FROM events").fetchone()
    if n != CAP:
        fail("after 20000 inserts events holds %d rows, want %d" % (n, CAP))
    if lo != max_id + 1 + 20000 - CAP:
        fail("trigger kept the wrong end: min id %d, want the newest %d ids"
             % (lo, max_id + 1 + 20000 - CAP))
    if hi != max_id + 20000:
        fail("max id %d, want %d" % (hi, max_id + 20000))

    # 2. The documented routine trims on demand (provided by migrate.py).
    sys.path.insert(0, ROOT)
    try:
        import migrate  # noqa: E402
    except Exception as exc:
        fail("migrate.py cannot be imported: %r" % exc)
    if not hasattr(migrate, "prune_events"):
        fail("migrate.py does not provide the documented prune_events() routine")
    try:
        migrate.prune_events()
    except Exception as exc:
        fail("prune_events() raised: %r" % exc)
    conn.commit()
    n = conn.execute("SELECT COUNT(*) FROM events").fetchone()[0]
    if n != CAP:
        fail("prune_events() left %d rows, want %d" % (n, CAP))

    # 3. Fresh connection, plain insert.
    conn2 = sqlite3.connect(DB)
    conn2.execute("INSERT INTO events(id, ts, kind, source, payload)"
                  " VALUES(?, ?, ?, ?, ?)",
                  (max_id + 30000, "2026-11-25T00:00:00", "audit", "gate", "z"))
    conn2.commit()
    n2 = conn2.execute("SELECT COUNT(*) FROM events").fetchone()[0]
    conn2.close()
    if n2 != CAP:
        fail("fresh-connection insert left %d rows, want %d" % (n2, CAP))

    # 4. Nothing else changed and no garbage.
    if snap(conn) != base:
        fail("another table or its rows changed")
    orphans = conn.execute(
        "SELECT COUNT(*) FROM events WHERE kind NOT IN (SELECT k FROM kinds)"
    ).fetchone()[0]
    if orphans:
        fail("%d events rows with unknown kind (row integrity broken)" % orphans)
    conn.close()
    print("PASS: retention enforced by the database itself, newest %d kept"
          % CAP)


if __name__ == "__main__":
    main()
