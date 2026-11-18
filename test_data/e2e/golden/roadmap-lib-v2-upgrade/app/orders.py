"""Order totals per customer."""
from recordio import open_reader, coerce


def order_totals(path):
    """Sum the amount column per customer, rounded to 2 places. The file is semicolon separated."""
    totals = {}
    with open_reader(path, sep=";", strip=True) as reader:
        for row in reader:
            amount = coerce(row["amount"], float, default=0.0)
            customer = row["customer"]
            totals[customer] = round(totals.get(customer, 0.0) + amount, 2)
    return totals
