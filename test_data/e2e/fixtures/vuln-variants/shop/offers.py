"""Discount offers."""

OFFER_COLUMNS = "id, code, title, region, discount, active"


def find_offer(conn, code):
    """Return (id, code, title, region, discount, active) for the offer with this code, or None.

    The code comes from the request (a coupon box), so it is passed as a bound parameter
    (SEC-ADV-2026-031).
    """
    row = conn.execute(
        f"SELECT {OFFER_COLUMNS} FROM offers WHERE code = ?",
        (code,),
    ).fetchone()
    return row


def active_offers(conn):
    """Return (id, code, title, region, discount) for every active offer, best discount first."""
    return conn.execute(
        f"SELECT id, code, title, region, discount FROM offers "
        f"WHERE active = 1 ORDER BY discount DESC, code"
    ).fetchall()
