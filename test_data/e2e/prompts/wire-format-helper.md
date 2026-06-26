Prices in `src/cart.js` are integer cents, and the program currently prints the raw total
(`Total: 1000`). Do two things:

1. Create a new file `src/format.js` that exports a `formatMoney(cents)` function returning the
   amount as a dollar string with two decimals and a leading `$` (e.g. `formatMoney(1000)` returns
   `"$10.00"`).
2. Import `formatMoney` in `src/cart.js` and use it on the cart total so the final line prints
   `Total: $10.00`.

Do not change the cart math itself - only format the output.
