"""Product reviews."""


def reviews_for_product(conn, sku):
    """Return (review id, customer e-mail, rating, body, created_at) for one product."""
    return conn.execute(
        "SELECT r.id, c.email, r.rating, r.body, r.created_at "
        "FROM reviews r "
        "JOIN customers c ON c.id = r.customer_id "
        "JOIN products p ON p.id = r.product_id "
        "WHERE p.sku = ? "
        "ORDER BY r.id",
        (sku,),
    ).fetchall()


def reviews_by_customer(conn, email):
    """Return (review id, product sku, rating, body, created_at) written by one customer."""
    return conn.execute(
        "SELECT r.id, p.sku, r.rating, r.body, r.created_at "
        "FROM reviews r "
        "JOIN products p ON p.id = r.product_id "
        "JOIN customers c ON c.id = r.customer_id "
        "WHERE c.email = ? "
        "ORDER BY r.created_at, r.id",
        (email,),
    ).fetchall()
