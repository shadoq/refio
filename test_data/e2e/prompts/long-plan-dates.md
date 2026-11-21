This is `daykit`, a small date library working on plain `YYYY-MM-DD` strings in UTC, without any date library. `addDays`, `diffDays` and `isWeekend` are built on `parseDate`.

Every module in `src/` is still a stub that raises `not implemented`. Implement all 5 of them. The expected behaviour of each one is defined only by its test in `tests/`; read the tests, they hold edge cases the names do not tell you. Some modules use others, so plan the order.

Keep your plan in the `tasks` tool: one step per module, and mark each step completed when its test passes.

Do not modify anything under `tests/`, nor `run-tests.js` or `package.json`.

The work is done only when `node run-tests.js` prints `SUMMARY passed 5/5`.
