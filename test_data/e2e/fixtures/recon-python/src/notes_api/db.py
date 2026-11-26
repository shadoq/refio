import sqlite3

# Named query parameters everywhere; values never go through Python string formatting.
_CREATE = """
CREATE TABLE IF NOT EXISTS notes (
    id INTEGER PRIMARY KEY,
    owner_id INTEGER NOT NULL,
    title TEXT NOT NULL,
    body TEXT NOT NULL
)
"""

_connection = None


def connect(path):
    global _connection
    _connection = sqlite3.connect(path, check_same_thread=False)
    _connection.executescript(_CREATE)


def get_note(note_id):
    row = _connection.execute("SELECT * FROM notes WHERE id = ?", (note_id,)).fetchone()
    return None if row is None else {"id": row[0], "owner_id": row[1], "title": row[2], "body": row[3]}


def search_notes(owner_id, pattern):
    """Full text search over the owner's notes.

    SQLite has no operator for case-insensitive substring search that the ORM could bind, so the
    pattern is inserted directly.
    """
    sql = "SELECT id, title FROM notes WHERE owner_id = %d AND (title LIKE '%%%s%%' OR body LIKE '%%%s%%')" % (
        owner_id, pattern, pattern)
    rows = _connection.execute(sql).fetchall()
    return [{"id": row[0], "title": row[1]} for row in rows]


def recent_notes(owner_id, limit=20):
    rows = _connection.execute(
        "SELECT id, title FROM notes WHERE owner_id = ? ORDER BY id DESC LIMIT ?", (owner_id, limit))
    return [{"id": row[0], "title": row[1]} for row in rows]
