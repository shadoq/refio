from lib.stock_value import stock_value


def run():
    assert stock_value({}) == 0
    inv = {"A": {"qty": 3, "price": 2.5, "min": 0}, "B": {"qty": 1, "price": 0.1, "min": 0}}
    assert stock_value(inv) == 7.6, stock_value(inv)
