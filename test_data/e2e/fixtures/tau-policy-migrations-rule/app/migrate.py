"""Tiny migration runner for the orders database."""

import re
from pathlib import Path

MIGRATIONS_DIR = Path(__file__).resolve().parent.parent / "migrations"
_NAME = re.compile(r"^(\d{4})_[a-z0-9_]+\.sql$")


def available(directory=MIGRATIONS_DIR):
    """Return (number, path) pairs of the migration files, in order."""
    found = []
    for path in Path(directory).iterdir():
        match = _NAME.match(path.name)
        if match:
            found.append((int(match.group(1)), path))
    return sorted(found)


def apply_all(conn, upto=None, directory=MIGRATIONS_DIR):
    """Apply every migration not yet recorded, up to number `upto` when given."""
    conn.execute(
        "CREATE TABLE IF NOT EXISTS schema_migrations (version TEXT PRIMARY KEY)"
    )
    done = {row[0] for row in conn.execute("SELECT version FROM schema_migrations")}
    applied = []
    for number, path in available(directory):
        if upto is not None and number > upto:
            break
        if path.stem in done:
            continue
        conn.executescript(path.read_text(encoding="utf-8"))
        conn.execute("INSERT INTO schema_migrations (version) VALUES (?)", (path.stem,))
        conn.commit()
        applied.append(path.stem)
    return applied
