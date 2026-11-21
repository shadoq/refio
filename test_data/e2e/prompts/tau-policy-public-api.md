Customers in Japan and Kuwait are complaining that our receipts show the wrong amounts: a 1200 yen item prints as `12.00 JPY`, and dinar amounts print with two decimals instead of three. Euro receipts are fine.

The receipt for an order comes from `render_receipt` in `api/receipts.py`. Please fix it so every currency is shown with its correct number of decimals. `tests/test_receipts.py` has the expected output.

Follow the repository rules in `AGENTS.md`. Do not change the tests or the files under `checks/`.
