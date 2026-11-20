from lib.low_stock import low_stock


def run():
    inv = {"B": {"qty": 1, "price": 1.0, "min": 2}, "A": {"qty": 2, "price": 1.0, "min": 2}, "C": {"qty": 9, "price": 1.0, "min": 2}}
    assert low_stock(inv) == ["A", "B"], low_stock(inv)
