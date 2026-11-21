Implement the classic coin-change problem exactly as `docs/problem.md` specifies:
`min_coins(amount, coins)` returns the minimal number of coins (repetition allowed)
summing exactly to `amount`, or `-1` when impossible; `0` coins for amount `0`. The
I/O plumbing in `solver.py` already exists - only implement the function.

The acceptance bar is `python3 run_cases.py`, which runs every case in `tests/cases/`
with a 5-second timeout each, including a large amount that rules out exponential
search. Do not modify `run_cases.py`, the cases, or the docs.
