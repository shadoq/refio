Complete `total_cost(prices, tax_rate)` in `pricing.py` exactly as its docstring
specifies: each `(amount, taxable)` pair contributes `amount`, and taxable amounts add
`amount * tax_rate` on top; the result is rounded to 2 decimals. The function must be
**pure** - never mutate the `prices` list (no sort/pop/append/element writes) and never
touch module-level state. The unit tests in `test_total_cost.py` are the acceptance bar
(two of them assert purity directly): `python3 -m unittest discover -p "test_*.py"` must
exit 0. Do not modify the tests.
