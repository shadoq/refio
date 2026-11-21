"""Write the customer summary report."""
from recordio import open_writer


def write_summary(path, totals):
    """Write customer,total rows sorted by customer; totals have 2 decimals."""
    rows = [{"customer": c, "total": "%.2f" % t} for c, t in sorted(totals.items())]
    with open_writer(path, columns=["customer", "total"]) as writer:
        writer.write_all(rows)
