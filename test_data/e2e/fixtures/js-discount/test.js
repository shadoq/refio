const assert = require("node:assert");
const { applyDiscount } = require("./src/discount");
const { checkout } = require("./src/checkout");
const { clearance } = require("./src/report");

// applyDiscount must honor an explicit rate argument (a fraction like 0.25).
assert.strictEqual(applyDiscount(100, 0.1), 90);
assert.strictEqual(applyDiscount(100, 0.25), 75);

// Existing callers must keep their original 10% behavior.
assert.deepStrictEqual(checkout([100, 200]), [90, 180]);
assert.strictEqual(clearance(50), 45);

console.log("OK");
