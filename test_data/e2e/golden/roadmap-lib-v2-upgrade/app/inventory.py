"""Stock level queries."""
from recordio import open_reader, coerce


def low_stock(path, threshold):
    """Sorted names of items whose qty is below threshold (unreadable qty counts as 0)."""
    names = []
    with open_reader(path, strip=True) as reader:
        for row in reader:
            if coerce(row["qty"], int, default=0) < threshold:
                names.append(row["name"])
    return sorted(names)
