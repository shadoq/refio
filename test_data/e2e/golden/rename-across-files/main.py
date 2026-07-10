from src.calc import compute_total
from src.report import render_report

items = [("apple", 1.5), ("pear", 2.0)]
assert compute_total(items) == 3.5, compute_total(items)
assert render_report(items) == "total: 3.50", render_report(items)
print("OK")
