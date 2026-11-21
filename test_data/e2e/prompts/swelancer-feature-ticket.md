Ticket OPS-214: CSV export for the orders report

Hi team,

Finance wants to pull the orders report into a spreadsheet every Monday. Right now `node src/cli.js report` only prints the fixed-width text table. Please add a CSV export and a couple of filters. Acceptance criteria below, our QA will check every one of them.

Acceptance criteria

1. `report.js` exports a `render(orders, options)` function. `options.format` is `"text"` (default) or `"csv"`. Any other format throws `Error("Unknown format: <value>")`.
2. CSV starts with the header `id,customer,status,total,placed_on`, then one row per order, lines separated by `\n`, with a trailing newline. With zero matching orders the output is just the header line.
3. `total` is dollars with exactly two decimals and no currency symbol (`120.50`).
4. Fields that contain a comma, a double quote or a line break are quoted, and double quotes inside them are doubled (RFC 4180).
5. CSV rows are sorted by `placed_on` ascending, then by `id` ascending as a number.
6. `options.status` is a comma separated list of statuses (`pending`, `paid`, `shipped`, `refunded`). Matching ignores case and surrounding spaces. An unknown status throws `Error("Unknown status: <value as typed, trimmed>")`.
7. `options.since` is `YYYY-MM-DD`, inclusive. Anything that is not a real calendar date throws `Error("Invalid date: <value>")`.
8. Filters combine with AND and apply to both formats. The text format keeps exactly today's layout and order, including the summary line (which then reflects the filtered orders).
9. CLI: `node src/cli.js report [--format text|csv] [--status a,b] [--since YYYY-MM-DD]`. On success print the result to stdout and exit 0. On any invalid input print the error message to stderr, print nothing to stdout and exit with code 2.

Tests for all of this are in `test/report.test.js` (run with `node --test test/report.test.js`). Please do not change them.

Thanks!
