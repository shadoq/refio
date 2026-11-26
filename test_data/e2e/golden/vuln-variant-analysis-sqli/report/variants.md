# Variant analysis - SEC-ADV-2026-031 (SQL injection)

Pattern searched for: a caller-supplied value spliced into the SQL text with an f-string,
`%` formatting, `.format()` or string concatenation, and the result handed to
`conn.execute()`. SEC-ADV-2026-031 fixed one occurrence in `shop/offers.py:find_offer`. This
sweep covers the other query modules of the package; every occurrence below was confirmed by
calling the function with `' OR '1'='1` and a UNION payload before the fix.

## Siblings

variant = shop/orders.py | orders_for_customer
variant = shop/orders.py | orders_between
variant = shop/customers.py | search_customers
variant = shop/customers.py | customers_in_region
variant = shop/inventory.py | products_in_category
variant = shop/reviews.py | reviews_by_customer
variant = shop/reports.py | top_customers_by_region

## How each one is reached

- `orders_for_customer`, `reviews_by_customer`: e-mail from the order history and review
  profile pages; `f-string` / `.format()` inside `WHERE c.email = '...'`.
- `orders_between`: both timestamps from the finance export date picker, concatenated.
- `search_customers`: search box, `%` formatting inside a `LIKE` pattern. A `'` closes the
  pattern, so `' OR '1'='1` lists every customer.
- `customers_in_region`, `top_customers_by_region`, `products_in_category`: region and
  category from the marketing pages, concatenated or formatted into `WHERE ... = '...'`.

All seven now pass the value as a `?` parameter. `search_customers` uses
`name LIKE '%' || ? || '%'`, so the wildcard semantics for ordinary input are unchanged.

## Checked and left alone

- `shop/offers.py:find_offer`: the function from the advisory, already fixed in 0.4.2.
- `customer_by_email`, `product_by_sku`, `reviews_for_product`, `low_stock`: bound parameters.
- `search_titles`: the concatenation only builds the bound parameter value, not the SQL.
- `customers_by_ids`: the f-string inserts `?` placeholders only; the ids are bound.
- `orders_in_status`: interpolates a constant fragment from an allow-list; unknown names raise
  `KeyError`, so no caller input reaches the SQL text.
- `active_offers`, `order_count`, `revenue_by_category`: no caller input.

## Verification

`python3 check.py` passes (18 functions probed with legitimate input and eight payload
classes) and `python3 -m unittest` still passes.
