"""Stock level queries."""
from recordio import read_records, parse_int


def low_stock(path, threshold):
    """Sorted names of items whose qty is below threshold (unreadable qty counts as 0)."""
    names = []
    for row in read_records(path):
        if parse_int(row["qty"]) < threshold:
            names.append(row["name"])
    return sorted(names)
