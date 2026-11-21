I started renaming "customer" to "client" across the shop code and got pulled onto something else. `TODO_RENAME.md` says where I stopped and how I do it. Please finish the rename in the rest of the project in the same style.

What I already changed (`shop/clients.py`, `shop/orders.py`) is final, leave those files exactly as they are, and do not edit the tests, they already use the new names. The tests currently fail because the remaining modules still use the old names; `python -m unittest discover -s tests -t .` must pass in the end.
