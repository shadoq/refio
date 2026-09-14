This project is a directory, not a single file. `src/legacy.js` is one module that has grown to hold four
unrelated jobs at once: inventory with batches and expiry dates, pricing with tiered discounts, coupons and
per-category tax, reporting in text and CSV, and a handful of date and rounding helpers everything uses.
`test/legacy.test.js` pins its behaviour and currently passes.

Split it into `src/inventory.js`, `src/pricing.js`, `src/report.js` and `src/dates.js`, each owning one of
those jobs, with the shared date and rounding helpers living in `src/dates.js` only. `src/legacy.js` stays
as the entry point: it requires the new modules with relative paths and re-exports exactly the same public
names it exports today, so every existing caller keeps working.

Change no behaviour. Every number, message, error text and output format must stay identical, including the
rounding and the padding in the reports. Do not modify `test/legacy.test.js`, and do not add dependencies:
Node's standard library only.

Each new module must require only what it actually uses, and no helper may end up copied into more than one
module. Run `node --test` from the project root yourself and keep running it until the whole suite passes
unchanged. Deliver the split with `src/legacy.js` as the facade.
