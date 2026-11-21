# Coin change (authoritative problem statement)

Given an amount and a set of coin denominations (each usable unlimited times), return
the **minimal number of coins** that sums to exactly the amount, or `-1` when it cannot
be formed. `amount == 0` needs `0` coins.

I/O contract (already implemented in `solver.py`'s `main`):

- stdin line 1: the amount (integer >= 0),
- stdin line 2: space-separated coin values (positive integers),
- stdout: the minimal coin count or `-1`.

The acceptance bar is `python3 run_cases.py`: it runs `solver.py` against every case in
`tests/cases/` with a 5-second timeout per case - including a large amount where an
exponential search will not finish in time. A correct-but-slow solution is a wrong
solution here.
