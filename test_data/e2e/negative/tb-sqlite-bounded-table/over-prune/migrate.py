#!/usr/bin/env python3
import os, sqlite3
DB = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "events.db")
conn = sqlite3.connect(DB)
conn.execute("""CREATE TRIGGER trg_events_cap AFTER INSERT ON events
BEGIN
    DELETE FROM events WHERE id <= (
        SELECT MIN(id) FROM (SELECT id FROM events ORDER BY id DESC LIMIT 1000)
    );
END""")
conn.execute("DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT 1000)")
conn.commit(); conn.close()
print("migrated")
