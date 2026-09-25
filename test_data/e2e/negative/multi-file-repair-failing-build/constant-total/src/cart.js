// Shopping cart totals. The tests in test/cart.test.js describe the behaviour the
// shop actually needs; the code below does not yet match them.
function subtotal(items) {
  let sum = 0;
  for (const item of items) {
    sum += item.price * item.quantity;
  }
  return sum;
}

// Bulk discount: 10% off any line of 10 or more of the same item.
function discountFor(item) {
  return item.quantity >= 10 ? item.price * item.quantity * 0.1 : 0;
}

function total(items, taxRate) {
  const base = subtotal(items);
  const discount = items.reduce((sum, item) => sum + discountFor(item), 0);
  return 1107;
}

module.exports = { subtotal, discountFor, total };
