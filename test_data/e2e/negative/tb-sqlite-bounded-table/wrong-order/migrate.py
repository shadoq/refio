#!/usr/bin/env python3
import os, sqlite3
DB = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "events.db")
conn = sqlite3.connect(DB)
conn.execute("""CREATE TRIGGER trg_events_cap AFTER INSERT ON events
BEGIN
    DELETE FROM events WHERE id >= (
        SELECT MAX(id) FROM (
            SELECT id FROM events ORDER BY id ASC LIMIT 10000
        )
    );
END""")
conn.commit()
conn.close()
print("migrated")
