"""Aggregate figures for the weekly jobs."""

TOP_LIMIT = 3


def revenue_by_category(conn):
    """Return (category, revenue) with the revenue of delivered items per product category."""
    return conn.execute(
        "SELECT p.category, ROUND(SUM(oi.qty * oi.unit_price), 2) "
        "FROM order_items oi "
        "JOIN products p ON p.id = oi.product_id "
        "GROUP BY p.category "
        "ORDER BY p.category"
    ).fetchall()


def top_customers_by_region(conn, region):
    """Return (name, number of orders, spend) for the best customers of one region."""
    return conn.execute(
        "SELECT c.name, COUNT(*) AS orders, ROUND(SUM(o.total), 2) AS spend "
        "FROM customers c "
        "JOIN orders o ON o.customer_id = c.id "
        "WHERE c.region = ? "
        "GROUP BY c.id "
        f"ORDER BY orders DESC, c.name LIMIT {TOP_LIMIT}",
        (region,),
    ).fetchall()
