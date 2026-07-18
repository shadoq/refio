"""Stage 3: load transformed rows into the (in-memory) sink and total them."""


def load(rows):
    total = sum(row["amount_dollars"] for row in rows)
    return {"rows": rows, "total_dollars": total}
