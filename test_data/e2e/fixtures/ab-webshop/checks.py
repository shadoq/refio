#!/usr/bin/env python3
"""Acceptance gate: cart/cart.json must be the unique optimal cart for the mission.

The optimum is recomputed by brute force from server/catalog.json (one item per list
category, total price within budget, maximal total rating). The optimum is unique in
this catalog, so the cart must match it exactly.
"""
import itertools
import json
import sys

BUDGET = 80.00
LIST = ["keyboard", "mouse", "monitor", "dock"]


def main() -> int:
    with open("server/catalog.json", encoding="utf-8") as fh:
        products = {p["id"]: p for p in json.load(fh)["products"]}
    try:
        with open("cart/cart.json", encoding="utf-8") as fh:
            cart = json.load(fh)
    except OSError:
        print("FAIL: cart/cart.json is missing")
        return 1
    except ValueError as exc:
        print(f"FAIL: cart/cart.json does not parse: {exc}")
        return 1
    items = cart.get("items") if isinstance(cart, dict) else None
    if not isinstance(items, list) or not all(isinstance(i, str) for i in items):
        print("FAIL: cart must be an object {\"items\": [product ids]}")
        return 1
    if len(items) != len(set(items)):
        print("FAIL: duplicate product ids in the cart")
        return 1
    unknown = [i for i in items if i not in products]
    if unknown:
        print(f"FAIL: unknown product ids: {unknown}")
        return 1
    cats = [products[i]["category"] for i in items]
    if sorted(cats) != sorted(LIST):
        print(f"FAIL: cart categories {sorted(cats)} != required {sorted(LIST)} "
              f"(exactly one item per category)")
        return 1
    total = round(sum(products[i]["price"] for i in items), 2)
    if total > BUDGET:
        print(f"FAIL: cart total {total:.2f} exceeds the {BUDGET:.2f} budget")
        return 1
    opts = {c: [k for k, p in products.items() if p["category"] == c] for c in LIST}
    best_rating, best_set = -1.0, None
    for combo in itertools.product(*[opts[c] for c in LIST]):
        price = round(sum(products[k]["price"] for k in combo), 2)
        if price > BUDGET:
            continue
        rating = round(sum(products[k]["rating"] for k in combo), 1)
        if rating > best_rating:
            best_rating, best_set = rating, set(combo)
    rating = round(sum(products[i]["rating"] for i in items), 1)
    if rating < best_rating:
        print(f"FAIL: cart rating {rating} is below the achievable optimum {best_rating} "
              f"(optimal set: {sorted(best_set)})")
        return 1
    if set(items) != best_set:
        print(f"FAIL: reported {sorted(items)}, optimum is {sorted(best_set)}")
        return 1
    print(f"PASS: cart is the unique optimum (rating {rating}, total {total:.2f})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
