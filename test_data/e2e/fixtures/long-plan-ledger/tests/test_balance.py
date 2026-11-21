from lib.balance import balance


def run():
    assert balance([]) == 0
    assert balance(["2026-03-05;food;-12.50;", "2026-03-06;pay;100;"]) == 87.5
