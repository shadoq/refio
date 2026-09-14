const test = require("node:test");
const assert = require("node:assert/strict");
const { subtotal, discountFor, total } = require("../src/cart");

test("subtotal adds every line", () => {
  assert.equal(subtotal([{ price: 250, quantity: 2 }, { price: 100, quantity: 3 }]), 800);
});

test("a line of exactly ten already earns the bulk discount", () => {
  assert.equal(discountFor({ price: 100, quantity: 10 }), 100);
});

test("a line of nine does not", () => {
  assert.equal(discountFor({ price: 100, quantity: 9 }), 0);
});

test("total applies tax on top of the discounted amount", () => {
  const items = [{ price: 100, quantity: 10 }];
  assert.equal(total(items, 0.23), 1107);
});
