"""Schema and seed rows for the shop catalogue database.

The seed is the single source of the demo data: `shop.db.connect()` loads it into an empty
database and `seed.py` (project root) writes it into a file. Keep the rows small and stable -
tests, reports and the security gate all expect this exact data.
"""

SCHEMA = """
CREATE TABLE customers (
    id INTEGER PRIMARY KEY,
    email TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    region TEXT NOT NULL,
    tier TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE TABLE products (
    id INTEGER PRIMARY KEY,
    sku TEXT NOT NULL UNIQUE,
    title TEXT NOT NULL,
    category TEXT NOT NULL,
    price REAL NOT NULL,
    stock INTEGER NOT NULL
);
CREATE TABLE orders (
    id INTEGER PRIMARY KEY,
    customer_id INTEGER NOT NULL REFERENCES customers(id),
    status TEXT NOT NULL,
    placed_at TEXT NOT NULL,
    total REAL NOT NULL
);
CREATE TABLE order_items (
    order_id INTEGER NOT NULL REFERENCES orders(id),
    product_id INTEGER NOT NULL REFERENCES products(id),
    qty INTEGER NOT NULL,
    unit_price REAL NOT NULL
);
CREATE TABLE reviews (
    id INTEGER PRIMARY KEY,
    product_id INTEGER NOT NULL REFERENCES products(id),
    customer_id INTEGER NOT NULL REFERENCES customers(id),
    rating INTEGER NOT NULL,
    body TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE TABLE offers (
    id INTEGER PRIMARY KEY,
    code TEXT NOT NULL UNIQUE,
    title TEXT NOT NULL,
    region TEXT,
    discount REAL NOT NULL,
    active INTEGER NOT NULL
);
"""

CUSTOMERS = [
    (1, "dana.palmer@example.com", "Dana Palmer", "Leinster", "gold", "2025-11-02"),
    (2, "fergus.ng@example.com", "Fergus Ng", "Munster", "silver", "2025-11-14"),
    (3, "maggie.obrien@example.ie", "Maggie O'Brien", "Munster", "gold", "2025-11-19"),
    (4, "wanda.union@example.com", "Wanda Union", "Connacht", "bronze", "2025-12-01"),
    (5, "liam.costello@example.com", "Liam Costello", "Leinster", "silver", "2025-12-03"),
    (6, "aisha.bello@example.com", "Aisha Bello", "Leinster", "gold", "2025-12-11"),
    (7, "petra.silva@example.com", "Petra Silva", "Munster", "bronze", "2025-12-20"),
    (8, "noah.fitzgerald@example.com", "Noah Fitzgerald", "Connacht", "silver", "2026-01-06"),
    (9, "ruby.kavanagh@example.com", "Ruby 100% Kavanagh", "Connacht", "bronze", "2026-01-15"),
    (10, "tom.darcy@example.com", "Tom D'Arcy", "Leinster", "gold", "2026-01-22"),
]

PRODUCTS = [
    (1, "ART-1001", "Artist's Easel", "Art Supplies", 89.5, 4),
    (2, "ART-1002", "Hog Bristle Brush Set", "Art Supplies", 12.0, 40),
    (3, "PAP-2001", "Cold Press Pad A3", "Paper", 18.75, 3),
    (4, "PAP-2002", "Sketchbook Pocket", "Paper", 9.99, 120),
    (5, "PEN-3001", "Fineliner 0.3", "Pens", 2.5, 400),
    (6, "PEN-3002", "Fountain Pen Medium", "Pens", 45.0, 0),
    (7, "PAP-2003", "Watercolour Block", "Paper", 24.0, 15),
    (8, "ART-1003", "Palette Knife", "Art Supplies", 6.25, 33),
    (9, "PEN-3003", "Ink Bottle Blue", "Pens", 11.0, 8),
    (10, "ART-1004", "Studio Easel XL", "Art Supplies", 210.0, 2),
    (11, "PEN-3004", "Marker Set", "Pens", 19.5, 60),
    (12, "PAP-2004", "Tracing Paper Roll", "Paper", 7.4, 75),
]

ORDERS = [
    (1, 1, "shipped", "2026-02-03T10:15:00", 102.25),
    (2, 1, "shipped", "2026-02-20T16:05:00", 18.75),
    (3, 2, "pending", "2026-02-21T09:40:00", 45.0),
    (4, 3, "shipped", "2026-02-05T11:00:00", 89.5),
    (5, 3, "cancelled", "2026-02-19T13:30:00", 9.99),
    (6, 4, "shipped", "2026-02-07T08:20:00", 6.25),
    (7, 4, "pending", "2026-02-22T17:45:00", 210.0),
    (8, 4, "shipped", "2026-02-10T12:00:00", 19.5),
    (9, 5, "shipped", "2026-02-12T15:10:00", 11.0),
    (10, 6, "shipped", "2026-02-14T19:25:00", 24.0),
    (11, 6, "shipped", "2026-02-18T10:05:00", 7.4),
    (12, 7, "cancelled", "2026-02-08T14:50:00", 12.0),
    (13, 8, "pending", "2026-02-23T09:15:00", 45.0),
    (14, 9, "shipped", "2026-02-16T16:40:00", 2.5),
    (15, 10, "shipped", "2026-02-24T11:30:00", 89.5),
]

ORDER_ITEMS = [
    (1, 1, 1, 89.5),
    (1, 2, 1, 12.0),
    (1, 5, 1, 2.5),
    (2, 3, 1, 18.75),
    (3, 6, 1, 45.0),
    (4, 1, 1, 89.5),
    (5, 4, 1, 9.99),
    (6, 8, 1, 6.25),
    (7, 10, 1, 210.0),
    (8, 11, 1, 19.5),
    (9, 9, 2, 5.5),
    (10, 7, 1, 24.0),
    (11, 12, 1, 7.4),
    (12, 2, 1, 12.0),
    (13, 6, 1, 45.0),
    (14, 5, 1, 2.5),
    (15, 8, 2, 6.25),
    (15, 1, 1, 76.5),
    (15, 5, 1, 2.5),
]

REVIEWS = [
    (1, 1, 1, 5, "Sturdy easel, holds a canvas twice its price.", "2026-02-08T10:00:00"),
    (2, 3, 3, 4, "Nice tooth, glue binding came loose on one pad.", "2026-02-09T18:30:00"),
    (3, 6, 2, 1, "Nib arrived scratched.", "2026-02-11T09:05:00"),
    (4, 2, 4, 5, "The 100% hog bristle brushes hold plenty of paint.", "2026-02-13T20:15:00"),
    (5, 9, 5, 3, "Blue is more of a navy.", "2026-02-15T07:45:00"),
    (6, 10, 6, 5, "Worth it for the studio.", "2026-02-17T13:20:00"),
    (7, 4, 7, 4, "Fits in a jacket pocket.", "2026-02-19T16:00:00"),
    (8, 5, 8, 5, "Consistent line, no skipping.", "2026-02-21T11:10:00"),
    (9, 11, 9, 4, "Good colours, caps crack easily.", "2026-02-22T15:35:00"),
    (10, 7, 10, 2, "Paper pillied after the second wash.", "2026-02-23T12:50:00"),
]

OFFERS = [
    (1, "SPRING-LEINSTER", "Spring framed prints", "Leinster", 0.15, 1),
    (2, "SPRING-MUNSTER", "Spring framed prints", "Munster", 0.15, 1),
    (3, "STUDENT-10", "Student discount", None, 0.1, 1),
    (4, "WINTER-20", "Winter clearance", None, 0.2, 0),
    (5, "GALWAY-ART", "Galway arts weekend", "Connacht", 0.12, 1),
    (6, "BLUE-MONDAY", "Blue Monday", None, 0.05, 0),
]

TABLES = {
    "customers": CUSTOMERS,
    "products": PRODUCTS,
    "orders": ORDERS,
    "order_items": ORDER_ITEMS,
    "reviews": REVIEWS,
    "offers": OFFERS,
}


def create_schema(conn):
    """Create the empty tables (the connection must not already contain them)."""
    conn.executescript(SCHEMA)


def populate(conn):
    """Create the schema when needed and insert every seed row."""
    create_schema(conn)
    for table, rows in TABLES.items():
        if not rows:
            continue
        placeholders = ", ".join("?" for _ in rows[0])
        conn.executemany(f"INSERT INTO {table} VALUES ({placeholders})", rows)
    conn.commit()
