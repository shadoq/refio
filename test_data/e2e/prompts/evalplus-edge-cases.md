`billing.split_bill` splits a bill between people in proportion to a weight each of them has. It works for the simple cases in `tests/test_basic.py`, but our finance team keeps finding bills where the shares do not add up to the total, and a few odd inputs are not handled at all.

Please make it robust. The stricter tests in `tests/test_extended.py` must pass together with the basic ones: `python -m unittest discover -s tests`. Do not change anything under `tests/`. Keep the function name and signature.
