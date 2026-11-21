from lib.remove_item import remove_item


def run():
    inv = {"A": {"qty": 5, "price": 1.0, "min": 0}}
    assert remove_item(inv, "A", 2)["A"]["qty"] == 3
    assert "A" not in remove_item(inv, "A", 5)
    for bad in (("A", 6), ("Z", 1)):
        try:
            remove_item(inv, *bad)
        except (ValueError, KeyError):
            pass
        else:
            raise AssertionError(f"removing {bad} must fail")
    assert inv["A"]["qty"] == 5
