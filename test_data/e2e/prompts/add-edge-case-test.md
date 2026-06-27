`capitalize` in `src/capitalize.js` already handles the empty-string edge case (it returns `""`
unchanged), but `test.js` has no test for it. Add one assertion to `test.js` that checks
`capitalize("")` returns `""`. Keep the existing assertions and do NOT modify
`src/capitalize.js` - this is a test-writing task only. The program must still print `OK` when run
with `node test.js`.
