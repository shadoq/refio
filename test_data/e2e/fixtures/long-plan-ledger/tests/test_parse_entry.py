from lib.parse_entry import parse_entry


def run():
    e = parse_entry("2026-03-05;food;-12.50;lunch")
    assert e == {"date": "2026-03-05", "category": "food", "amount": -12.5, "note": "lunch"}, e
    assert parse_entry("2026-03-06;pay;100;")["note"] == ""
    try:
        parse_entry("broken")
    except ValueError:
        pass
    else:
        raise AssertionError("a broken line must raise ValueError")
