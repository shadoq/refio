from lib.by_category import by_category


def run():
    lines = ["2026-03-05;food;-12.50;", "2026-03-06;food;-7.50;", "2026-03-06;pay;100;"]
    assert by_category(lines) == {"food": -20.0, "pay": 100.0}, by_category(lines)
