"""Connection helpers for the shop package."""

import os
import sqlite3

from shop import seed


def _has_tables(conn):
    row = conn.execute(
        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'customers'"
    ).fetchone()
    return bool(row[0])


def connect(path=None):
    """Open the catalogue database.

    Without `path` the database lives in memory. Without `path`, or for a file that does not
    contain the schema yet, the seed rows from `shop/seed.py` are loaded first, so callers
    always start from the same data.
    """
    if path:
        folder = os.path.dirname(os.path.abspath(path))
        os.makedirs(folder, exist_ok=True)
    conn = sqlite3.connect(path or ":memory:")
    conn.execute("PRAGMA foreign_keys = ON")
    if not _has_tables(conn):
        seed.populate(conn)
    return conn
