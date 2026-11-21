# Business questions - shop.db (read-only!)

The `shop.db` database holds three months of orders. Revenue rules used by every
question: an order contributes `SUM(qty * unit_price_at_sale)` over its order_items;
**cancelled orders never contribute** (returned ones still count - the goods did ship).
Ties are resolved deterministically (see the individual questions).

Answer the eight questions in `answers/answers.json` using exactly the keys below
(a flat JSON object, no extra keys):

- `q1_total_revenue` - revenue over all non-cancelled orders, rounded to 2 decimals.
- `q2_top_customer` - name of the customer with the highest non-cancelled revenue.
- `q3_returned_orders` - how many orders have status `returned`.
- `q4_best_seller` - product name with the most units sold in non-cancelled orders.
- `q5_april_revenue` - revenue from non-cancelled orders placed in April 2026
  (`placed_at` starting `2026-04-`), rounded to 2 decimals.
- `q6_emea_customers` - how many customers are in region `EMEA`.
- `q7_avg_order_value` - mean over non-cancelled orders of each order's total, rounded
  to 2 decimals.
- `q8_most_orders_customer_id` - the customer id with the most orders regardless of
  status; if tied, the smallest id.

`python3 checks.py` must exit 0 (it recomputes all eight values from `shop.db` and
compares your file; money values are compared rounded to 2 decimals). The database is
read-only for this exercise - do not modify it, the questions or the gate.
