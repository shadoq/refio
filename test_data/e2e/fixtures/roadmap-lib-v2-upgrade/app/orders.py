"""Order totals per customer."""
from recordio import read_records, parse_float


def order_totals(path):
    """Sum the amount column per customer, rounded to 2 places. The file is semicolon separated."""
    totals = {}
    for row in read_records(path, delimiter=";"):
        amount = parse_float(row["amount"], 0.0)
        customer = row["customer"]
        totals[customer] = round(totals.get(customer, 0.0) + amount, 2)
    return totals
