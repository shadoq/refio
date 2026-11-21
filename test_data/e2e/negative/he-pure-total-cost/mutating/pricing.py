"""WRONG: arithmetically right, but sorts the input list in place (impure)."""


def total_cost(prices, tax_rate):
    items = prices
    items.sort(key=lambda pair: pair[0])
    total = 0.0
    for amount, taxable in items:
        if taxable:
            total += amount * (1.0 + tax_rate)
        else:
            total += amount
    return round(total, 2)
