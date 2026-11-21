from lib.restock_plan import restock_plan


def run():
    inv = {"A": {"qty": 2, "price": 1.0, "min": 2}, "B": {"qty": 9, "price": 1.0, "min": 2}}
    assert restock_plan(inv, 5) == {"A": 3}, restock_plan(inv, 5)
    assert restock_plan({}, 5) == {}
