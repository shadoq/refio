"""Products."""


def product_by_sku(conn, sku):
    """Return (id, sku, title, category, price, stock) for one product, or None."""
    return conn.execute(
        "SELECT id, sku, title, category, price, stock FROM products WHERE sku = ?",
        (sku,),
    ).fetchone()


def search_titles(conn, term):
    """Return (sku, title) for products whose title contains `term`."""
    pattern = "%" + term + "%"
    return conn.execute(
        "SELECT sku, title FROM products WHERE title LIKE ? ORDER BY sku",
        (pattern,),
    ).fetchall()


def products_in_category(conn, category):
    """Return (sku, title, price, stock) for one category page, most expensive first."""
    sql = (
        "SELECT sku, title, price, stock FROM products "
        f"WHERE category = '{category}' "
        "ORDER BY price DESC, sku"
    )
    return conn.execute(sql).fetchall()


def low_stock(conn, threshold):
    """Return (sku, title, stock) for products with stock below `threshold`."""
    return conn.execute(
        "SELECT sku, title, stock FROM products WHERE stock < ? ORDER BY stock, sku",
        (threshold,),
    ).fetchall()
