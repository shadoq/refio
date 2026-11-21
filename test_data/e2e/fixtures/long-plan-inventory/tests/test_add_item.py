from lib.add_item import add_item


def run():
    inv = add_item({}, "A", 3, 2.5)
    assert inv == {"A": {"qty": 3, "price": 2.5, "min": 0}}, inv
    inv2 = add_item(inv, "A", 2, 3.0)
    assert inv2["A"] == {"qty": 5, "price": 3.0, "min": 0}, inv2
    assert inv["A"]["qty"] == 3, "the input must not change"
    try:
        add_item({}, "B", -1, 1.0)
    except ValueError:
        pass
    else:
        raise AssertionError("a negative quantity must raise ValueError")
