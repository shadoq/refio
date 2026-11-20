from lib.monthly_totals import monthly_totals


def run():
    lines = ["2026-03-05;food;-10;", "2026-04-01;pay;50;", "2026-03-30;pay;5;"]
    assert monthly_totals(lines) == [("2026-03", -5.0), ("2026-04", 50.0)], monthly_totals(lines)
