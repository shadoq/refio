# storefront cart maths

`cart.js` prices a checkout cart: `add(sku, unitPrice, qty)` collects lines,
`subtotal()`, `discountedSubtotal(rate)` and `total(rate)` produce the dollar
figures for the receipt, with VAT (`TAX_RATE`) applied on the discounted
subtotal. The catalog feed sends unit prices as dollar amounts.

The finance export compares our totals against the payment provider's, so the
numbers must be exact to the cent.

Run the acceptance gate with:

    node checks.js
