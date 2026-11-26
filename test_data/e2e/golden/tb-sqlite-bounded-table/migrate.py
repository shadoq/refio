#!/usr/bin/env python3
"""Migration: enforce the retention contract on data/events.db.

- trg_events_cap: AFTER INSERT trigger keeping the newest 10000 rows.
- prune_events(): registered per connection by this script (python3
  sqlite3 create_function) and executed once; check.py registers the same
  routine through migrate.py's register() helper before calling it.
Idempotent.
"""
import os
import sqlite3

ROOT = os.path.dirname(os.path.abspath(__file__))
DB = os.path.join(ROOT, "data", "events.db")
CAP = 10000

TRIGGER = """
CREATE TRIGGER IF NOT EXISTS trg_events_cap
AFTER INSERT ON events
BEGIN
    DELETE FROM events WHERE id IN (
        SELECT id FROM events ORDER BY id DESC LIMIT -1 OFFSET 10000
    );
END
"""


def prune_events():
    conn = sqlite3.connect(DB)
    conn.execute("DELETE FROM events WHERE id NOT IN"
                 " (SELECT id FROM events ORDER BY id DESC LIMIT ?)", (CAP,))
    conn.commit()
    conn.close()
    return CAP


def register(conn):
    conn.create_function("prune_events", 0, prune_events)


def main():
    conn = sqlite3.connect(DB)
    conn.execute(TRIGGER)
    conn.commit()
    conn.close()
    prune_events()
    print("migrated: cap %d enforced" % CAP)


if __name__ == "__main__":
    main()
