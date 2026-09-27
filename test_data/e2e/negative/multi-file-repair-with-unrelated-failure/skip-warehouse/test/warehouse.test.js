const test = require("node:test");
const assert = require("node:assert/strict");
const { stockLevel } = require("../src/warehouse");

test.skip("the warehouse service reports stock for a known SKU", () => {
  assert.equal(stockLevel("SKU-1001"), 5);
});
