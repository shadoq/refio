"""Harness-owned check of the paging bug; the agent must not edit this file."""
from app.paging import page

items = list(range(1, 8))
assert page(items, 1, 3) == [1, 2, 3], page(items, 1, 3)
assert page(items, 3, 3) == [7], page(items, 3, 3)
assert page(items, 4, 3) == [], page(items, 4, 3)
print("ok")
