`shop/orders.py` has one long `process_order(order, db, mailer)` that validates the order, prices it, saves it and emails the customer.

Plan splitting it into four functions: `validate_order`, `price_order`, `save_order` and `notify_customer`, with `process_order` calling them in that order. The behaviour must not change, so first pin today's behaviour with tests.

New files may go only under `tests/`.

Do not change any file: this is planning only.

Answer with the plan as a numbered list (`1.`, `2.`, ...), one step per item, in the order the work must be done. In every step name the exact files it touches. Name only files that exist in the project, or new files in the places listed above.
