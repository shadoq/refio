"""Write the customer summary report."""
from recordio import write_records


def write_summary(path, totals):
    """Write customer,total rows sorted by customer; totals have 2 decimals."""
    rows = [{"customer": c, "total": "%.2f" % t} for c, t in sorted(totals.items())]
    write_records(path, rows, ["customer", "total"])
