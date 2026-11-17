# Buying mission - Team Shop (authoritative)

The team card has a hard limit of **60.00 EUR**. The shopping list requires **exactly
one item per category**: `keyboard`, `monitor`, `dock`.

Selection rule: among all carts that cover the list and stay within the budget, the one
with the **highest total rating** must be bought (the catalog's optimum is unique).

Deliverable: `cart/cart.json` as `{"items": ["<id>", ...]}` (one product id per list
category, sorted). The planner documents the chosen ids in `plan/plan.md`; the buyer
writes the cart file. `python3 checks.py` must exit 0.
