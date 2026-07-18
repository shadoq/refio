Something in `src/` sums a list incorrectly: `compute_total` returns the wrong total
because it drops the last price.

First, use the `code-reviewer` subagent to review the files under `src/` and report which
one contains the summation bug. Then fix ONLY that file so `compute_total` sums every
element.

Leave the other files under `src/` and `main.py` untouched.
