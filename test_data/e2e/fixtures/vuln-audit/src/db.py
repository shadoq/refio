"""SQLite storage for the notes service."""
import sqlite3

SCHEMA = """
CREATE TABLE notes (id INTEGER PRIMARY KEY, title TEXT, body TEXT);
"""

SEED = [
    (1, "welcome", "First note: the service is up."),
    (2, "shopping list", "milk, eggs, bread"),
    (3, "standup notes", "release cut on Friday"),
]


def connect():
    conn = sqlite3.connect(":memory:")
    conn.executescript(SCHEMA)
    conn.executemany("INSERT INTO notes (id, title, body) VALUES (?, ?, ?)", SEED)
    conn.commit()
    return conn
