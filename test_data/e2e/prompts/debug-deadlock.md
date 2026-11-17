The transfer ledger in `src/accounts.py` intermittently hangs: ops reports that
concurrent opposite-direction transfers (A->B while B->A) wedge the worker until it is
killed.

- `src/accounts.py` - `transfer(src, dst, amount)` locks `src` then `dst`, across a
  **50 ms in-flight processing window** (`time.sleep(0.05)`). The window models real
  in-flight processing and must stay.
- `checks.py` - the acceptance gate (do not modify it): it runs the deadly pair
  (A->B and B->A concurrently), waits with a timeout, and checks both the completion and
  the exact resulting balances.
- `README.md` - service notes.

Your task: fix the lock-ordering deadlock in `transfer` so opposite-direction transfers
cannot interleave - acquire the two locks in a **deterministic order** (for example by
account id, via a small helper) while keeping the 50 ms window between acquiring the first
and second lock. `python3 checks.py` must exit 0. Do not modify the gate or the README.
