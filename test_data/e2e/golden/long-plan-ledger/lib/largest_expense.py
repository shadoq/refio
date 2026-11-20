from lib.parse_entry import parse_entry


def largest_expense(lines):
    costs = [parse_entry(l) for l in lines]
    costs = [e for e in costs if e["amount"] < 0]
    return min(costs, key=lambda e: e["amount"]) if costs else None
