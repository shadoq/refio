The `formatMoney` function renders amounts incorrectly: 1099 cents shows as `$10`
instead of `$10.99`, because it drops the remaining cents.

Fix the `formatMoney` function so the cents are included in the output (for example,
1099 should render as `$10.99`).

Change only the `formatMoney` function definition. Do not modify any of the files that
call it - the call sites are already correct.
