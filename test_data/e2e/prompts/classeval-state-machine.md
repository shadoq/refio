We are replacing a pile of `if status == ...` checks in the order service with a proper state machine. `orders.py` has the full specification in its module docstring (states, events, guards, failure rules) and the class skeleton with docstrings, but the `Order` methods are not implemented yet.

Implement `Order` so that it follows the specification exactly. Keep the public names (`Order`, `OrderError`, `IllegalTransition`, `GuardFailed`, `REFUND_WINDOW_SECONDS`). `python -m unittest discover -s tests` must pass and `tests/` must stay unchanged.
