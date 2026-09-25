const test = require("node:test");
const assert = require("node:assert/strict");
const { lineTotal, withTax, orderTotal } = require("../src/cart");

test("nine items pay full price", () => {
  assert.equal(lineTotal(100, 9), 900);
});

test("ten items already earn the 10% bulk discount", () => {
  assert.equal(lineTotal(100, 10), 900);
});

test("eleven items earn the 10% bulk discount", () => {
  assert.equal(lineTotal(100, 11), 990);
});

test("the discounted price is rounded down to whole cents", () => {
  // 101 * 11 = 1111 cents; 90% of that is 999.9 cents.
  assert.equal(lineTotal(101, 11), 999);
});

test("zero items cost nothing", () => {
  assert.equal(lineTotal(100, 0), 0);
});

test("tax is added on top of the net amount and rounded down", () => {
  // 23% of 999 cents is 229.77 cents.
  assert.equal(withTax(999, 23), 1228);
});

test("order total discounts bulk lines before adding tax", () => {
  const lines = [
    { unitPriceCents: 100, quantity: 10 },
    { unitPriceCents: 250, quantity: 2 },
  ];
  // Net 900 + 500 = 1400 cents; 8% tax is 112 cents.
  assert.equal(orderTotal(lines, 8), 1512);
});

test("an empty order costs nothing", () => {
  assert.equal(orderTotal([], 23), 0);
});
