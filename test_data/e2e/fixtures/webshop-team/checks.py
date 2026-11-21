#!/usr/bin/env python3
"""Acceptance gate: cart/cart.json must be the unique optimal cart for the mission
(brute-forced from server/catalog.json)."""
import itertools
import json
import sys

BUDGET = 60.00
LIST = ["keyboard", "monitor", "dock"]


def main() -> int:
    with open("server/catalog.json", encoding="utf-8") as fh:
        products = {p["id"]: p for p in json.load(fh)["products"]}
    try:
        with open("cart/cart.json", encoding="utf-8") as fh:
            cart = json.load(fh)
    except OSError:
        print("FAIL: cart/cart.json is missing")
        return 1
    items = cart.get("items") if isinstance(cart, dict) else None
    if not isinstance(items, list):
        print("FAIL: cart must be an object {\"items\": [product ids]}")
        return 1
    cats = sorted(products[i]["category"] for i in items if i in products)
    if sorted(set(cats)) != sorted(LIST) or len(items) != 3:
        print(f"FAIL: cart {items} does not cover exactly one item per list category")
        return 1
    total = round(sum(products[i]["price"] for i in items), 2)
    if total > BUDGET:
        print(f"FAIL: cart total {total:.2f} exceeds the budget")
        return 1
    opts = {c: [k for k, p in products.items() if p["category"] == c] for c in LIST}
    best_rating, best_set = -1.0, None
    for combo in itertools.product(*[opts[c] for c in LIST]):
        if round(sum(products[k]["price"] for k in combo), 2) > BUDGET:
            continue
        rating = round(sum(products[k]["rating"] for k in combo), 1)
        if rating > best_rating:
            best_rating, best_set = rating, set(combo)
    rating = round(sum(products[i]["rating"] for i in items), 1)
    if set(items) != best_set:
        print(f"FAIL: reported {sorted(items)}, optimum is {sorted(best_set)} (rating {best_rating})")
        return 1
    print(f"PASS: cart is the unique optimum (rating {rating}, total {total:.2f})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
