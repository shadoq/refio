from lib.largest_expense import largest_expense


def run():
    lines = ["2026-03-05;food;-12.50;lunch", "2026-03-06;rent;-900;march", "2026-03-06;pay;1000;"]
    assert largest_expense(lines)["note"] == "march"
    assert largest_expense(["2026-03-06;pay;1000;"]) is None
