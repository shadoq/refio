"""Customer lookups and search."""


def customer_by_email(conn, email):
    """Return (id, email, name, region, tier) for one customer, or None."""
    return conn.execute(
        "SELECT id, email, name, region, tier FROM customers WHERE email = ?",
        (email,),
    ).fetchone()


def customers_by_ids(conn, ids):
    """Return (id, email, name, region, tier) for the given customer ids, ordered by id.

    `ids` comes from the order bulk-edit screen (check boxes).
    """
    if not ids:
        return []
    placeholders = ", ".join("?" for _ in ids)
    return conn.execute(
        f"SELECT id, email, name, region, tier FROM customers "
        f"WHERE id IN ({placeholders}) ORDER BY id",
        list(ids),
    ).fetchall()


def search_customers(conn, term):
    """Return (id, email, name, region) for customers whose name contains `term`."""
    return conn.execute(
        "SELECT id, email, name, region FROM customers "
        "WHERE name LIKE '%' || ? || '%' "
        "ORDER BY name",
        (term,),
    ).fetchall()


def customers_in_region(conn, region):
    """Return (id, email, name, tier) for the customers of one region, ordered by name."""
    return conn.execute(
        "SELECT id, email, name, tier FROM customers "
        "WHERE region = ? "
        "ORDER BY name",
        (region,),
    ).fetchall()
