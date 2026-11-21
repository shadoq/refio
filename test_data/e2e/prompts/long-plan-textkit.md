This is `textkit`, a small text library. `wrap` is built on `words`, and `titleCase` on `capitalize`.

Every module in `src/` is still a stub that raises `not implemented`. Implement all 6 of them. The expected behaviour of each one is defined only by its test in `tests/`; read the tests, they hold edge cases the names do not tell you. Some modules use others, so plan the order.

Keep your plan in the `tasks` tool: one step per module, and mark each step completed when its test passes.

Do not modify anything under `tests/`, nor `run-tests.js` or `package.json`.

The work is done only when `node run-tests.js` prints `SUMMARY passed 6/6`.
