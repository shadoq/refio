import sqlite3

from app.models import User


def connect(path=":memory:"):
    return sqlite3.connect(path)


def insert_user(conn, user):
    conn.execute("INSERT INTO users (id, name) VALUES (?, ?)", (user.id, user.name))


def get_user(conn, user_id):
    row = conn.execute("SELECT id, name FROM users WHERE id = ?", (user_id,)).fetchone()
    return User(*row) if row else None
