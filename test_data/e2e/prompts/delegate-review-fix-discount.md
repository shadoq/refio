There is a bug in `src/discount.py`.

First, use the `code-reviewer` subagent to review `src/discount.py` and identify the
problem. Then apply the fix yourself so the function behaves correctly.

Per the docstring, orders totaling exactly 100 must already qualify for the 10 percent
discount, but the current code rejects the boundary value. Fix the boundary; do not edit
`main.py`. When done, `python3 main.py` must print `OK`.
