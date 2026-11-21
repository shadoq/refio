# Buying mission - Mini Shop (authoritative)

The procurement card for the new workstation pool has a hard limit of **80.00 EUR** for
this order. The shopping list requires **exactly one item per category**:

    keyboard, mouse, monitor, dock

Selection rule: among all carts that cover the list and stay within the budget, the one
with the **highest total rating** must be bought (ratings are per product, one decimal;
ties do not occur in this catalog). Price is the only constraint - ratings are the
objective, not price.

Deliverable: `cart/cart.json` of the form

```json
{ "items": ["p2", "p4", "p8", "p11"] }
```

(product ids, one per list category, sorted). `python3 checks.py` must exit 0 - it
brute-forces the whole catalog and compares your cart with the unique optimum. The
catalog lives in `server/catalog.json` (also served by the shop over HTTP at
`/catalog`); the shop is read-only.
