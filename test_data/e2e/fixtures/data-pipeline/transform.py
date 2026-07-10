"""Stage 2: transform raw rows into report rows with a dollar amount."""


def normalize_amount(amount_cents):
    # Convert integer cents to a dollar amount.
    return amount_cents // 100


def transform(rows):
    out = []
    for row in rows:
        out.append({"id": row["id"], "amount_dollars": normalize_amount(row["amount_cents"])})
    return out
