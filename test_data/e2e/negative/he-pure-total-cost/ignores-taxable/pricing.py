"""WRONG: applies the tax rate to every item, taxable or not."""


def total_cost(prices, tax_rate):
    total = sum(amount * (1.0 + tax_rate) for amount, _ in prices)
    return round(total, 2)
