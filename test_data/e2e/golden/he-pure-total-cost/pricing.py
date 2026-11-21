"""Basket pricing - complete the documented function as a PURE function.

The docstring is the contract; the unit tests in test_total_cost.py are the acceptance
bar (they are not to be modified). Purity is asserted: the input must never be mutated
and the function must not touch module state.
"""


def total_cost(prices, tax_rate):
    """Total cost of a basket: every (amount, taxable) pair contributes `amount`,
    plus tax on the taxable ones (amount * (1 + tax_rate)). The result is rounded to
    2 decimals.

    The function must be pure: it must not modify `prices` (no sort/pop/append on it,
    no element rewrites) and must not read or write any module-level state.

    >>> total_cost([(10.0, True), (5.0, False)], 0.2)
    17.0
    >>> total_cost([], 0.5)
    0.0
    >>> total_cost([(2.5, True), (3.0, True)], 0.25)
    6.88
    """
    total = 0.0
    for amount, taxable in prices:
        if taxable:
            total += amount * (1.0 + tax_rate)
        else:
            total += amount
    return round(total, 2)
