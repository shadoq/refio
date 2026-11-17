## Finance flagged the cart totals: off by cents

### Symptom

The nightly reconciliation against the payment provider fails on three kinds
of carts, and support keeps getting tickets where the receipt differs from
the charged amount by one or two cents:

1. A cart with three items priced `0.1`, `0.2` and `0.3` dollars sums to
   `0.6000000000000001` instead of `0.6`.
2. A 10% discount on 3x 10.05 totals `27.15` instead of `27.14` - the
   discount rounds the discounted *unit* price to cents and then multiplies
   by quantity, so the per-unit rounding error is scaled by qty.
3. Totals with VAT drift again after the multiplication, and
   `parseFloat(toFixed(2))` silently re-introduces float representation error
   instead of keeping the number cent-exact.

### How to reproduce

    node -e "
    const { Cart } = require('./cart');
    const c = new Cart().add('cable', 10.05, 3);
    console.log(c.discountedSubtotal(0.1));  // 27.149999... or 27.15, expected 27.14
    const d = new Cart().add('a', 0.1, 1).add('b', 0.2, 1).add('c', 0.3, 1);
    console.log(d.subtotal());               // 0.6000000000000001, expected 0.6
    "

### Expected behaviour

Do the cart maths in **integer cents** end to end: convert each unit price to
cents exactly once when the line is added, sum lines as integers, apply the
discount to the LINE total and round once (never round a unit price and
multiply), and round VAT on the final cent amount. On top of the existing
dollar-number methods, expose the cents-level accessors the receipt printer
and the finance export need:

- `subtotalCents()` and `discountedSubtotalCents(rate)` - integer cents
- `totalCents(rate = 0)` - integer cents, VAT included
- `formatCents(cents)` - render cents as a dollar string (`3338` -> `"33.38"`)

The dollar-number methods (`subtotal`, `discountedSubtotal`, `total`) must
keep their signatures and be derived from the cents, so `subtotal() === 0.6`
holds exactly for the drift cart above.

### Your task

1. Reproduce all three reports against `cart.js`.
2. Rewrite the cart maths in `cart.js` in place so every total is computed
   in integer cents as described, with the new cents-level accessors added.
3. Run the gate: `node checks.js` - it replays the three finance reports, the
   accessor contract and the edge cases, and must pass.

Do not modify `checks.js` or the README: the fix belongs in `cart.js`.
