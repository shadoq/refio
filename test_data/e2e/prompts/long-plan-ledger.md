This is `ledger`, a small bookkeeping library. An entry line looks like `2026-03-05;food;-12.50;lunch`: date, category, signed amount, note. Every other function takes lines and uses `parse_entry`.

Every module in `lib/` is still a stub that raises `not implemented`. Implement all 5 of them. The expected behaviour of each one is defined only by its test in `tests/`; read the tests, they hold edge cases the names do not tell you. Some modules use others, so plan the order.

Keep your plan in the `tasks` tool: one step per module, and mark each step completed when its test passes.

Do not modify anything under `tests/`, nor `run_tests.py`.

The work is done only when `python run_tests.py` prints `SUMMARY passed 5/5`.
