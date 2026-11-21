A bad import left `crm.db` violating every data invariant: item rows pointing at orders
that do not exist, an order pointing at a missing customer, duplicate accounts sharing an
email (one of them a different id holding real orders), a customer with a NULL email, and
prices imported as TEXT with comma decimals (`48,0` means 48.0).

Read `docs/invariants.md` first - it defines the merge rules (keep the lowest id, re-point
the duplicate's orders, remove the NULL-email account with its orders) and the money
conservation rule (the repaired `SUM(qty*unit_price)` must be exactly 877.0 - money may
not be created or destroyed).

Your task: repair `crm.db` **in place** (delete/merge/normalize, any tool is fine) until
`python3 checks.py` exits 0 - it asserts each invariant plus the revenue digest. Do not
modify the docs or the gate.
