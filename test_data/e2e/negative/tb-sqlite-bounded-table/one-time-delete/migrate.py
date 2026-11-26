#!/usr/bin/env python3
import os, sqlite3
DB = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "events.db")
conn = sqlite3.connect(DB)
conn.execute("DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT 10000)")
conn.commit(); conn.close()
print("pruned once")
