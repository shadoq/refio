"""Load inventory items from a CSV file."""
from recordio import open_reader, coerce


def load_items(path):
    """Return a list of {"sku", "name", "qty"}; an unreadable qty becomes 0."""
    items = []
    with open_reader(path, strip=True) as reader:
        for row in reader:
            items.append({
                "sku": row["sku"],
                "name": row["name"],
                "qty": coerce(row["qty"], int, default=0),
            })
    return items
