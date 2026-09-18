const test = require("node:test");
const assert = require("node:assert/strict");
const { receiptLine } = require("../src/index");

test("a receipt line groups thousands so long amounts stay readable", () => {
  assert.equal(receiptLine("Total", 123456789), "Total: 1 234 567.89 PLN");
});

test("a short amount is unchanged apart from the currency", () => {
  assert.equal(receiptLine("Coffee", 1250), "Coffee: 12.50 PLN");
});
