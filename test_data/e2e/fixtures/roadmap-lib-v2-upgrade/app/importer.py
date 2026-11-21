"""Load inventory items from a CSV file."""
from recordio import read_records, parse_int


def load_items(path):
    """Return a list of {"sku", "name", "qty"}; an unreadable qty becomes 0."""
    items = []
    for row in read_records(path):
        items.append({
            "sku": row["sku"],
            "name": row["name"],
            "qty": parse_int(row["qty"], 0),
        })
    return items
