"""Orders.

Statuses stored in the orders table: pending, shipped, cancelled.
"""

# Named groups for the order overview screens. The fragments are constants, never input.
STATUS_GROUPS = {
    "open": "status = 'pending'",
    "closed": "status IN ('shipped', 'cancelled')",
}


def orders_for_customer(conn, email):
    """Return (order id, status, placed_at, total) for the orders of one customer e-mail."""
    sql = (
        "SELECT o.id, o.status, o.placed_at, o.total FROM orders o "
        "JOIN customers c ON c.id = o.customer_id "
        f"WHERE c.email = '{email}' "
        "ORDER BY o.placed_at, o.id"
    )
    return conn.execute(sql).fetchall()


def orders_between(conn, start, end):
    """Return (order id, customer id, status, total) for orders placed in [start, end).

    `start` and `end` are ISO timestamps from the date picker on the finance export page.
    """
    sql = (
        "SELECT id, customer_id, status, total FROM orders "
        "WHERE placed_at >= '" + start + "' AND placed_at < '" + end + "' "
        "ORDER BY id"
    )
    return conn.execute(sql).fetchall()


def orders_in_status(conn, group):
    """Return (id, status, total) for one of the named status groups ("open" or "closed").

    Unknown group names raise KeyError.
    """
    clause = STATUS_GROUPS[group]
    return conn.execute(f"SELECT id, status, total FROM orders WHERE {clause} ORDER BY id").fetchall()


def order_count(conn):
    """Return the number of orders in the database."""
    return conn.execute("SELECT COUNT(*) FROM orders").fetchone()[0]
