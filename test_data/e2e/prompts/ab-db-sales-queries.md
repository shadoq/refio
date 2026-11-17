Analyst run on a small commerce database (`shop.db`, schema: customers, products,
orders, order_items). The finance team asked eight questions - they are listed in
`docs/questions.md` together with the revenue rules (cancelled orders never contribute;
returned ones do; money rounded to 2 decimals).

Your task: run the queries against `shop.db` and write all eight answers to
`answers/answers.json` as a flat JSON object using exactly the keys from the questions
doc (`q1_total_revenue` ... `q8_most_orders_customer_id`, no extra keys). `python3
checks.py` must exit 0 - it recomputes every value from the database and compares your
file. Treat the database, the questions and the gate as read-only.
