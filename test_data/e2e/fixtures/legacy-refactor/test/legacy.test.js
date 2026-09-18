// The behaviour of the shop module, pinned through its public API. The suite must keep
// passing unchanged after the module is split, which is what makes the split provable.
const { test } = require("node:test");
const assert = require("node:assert/strict");
const shop = require("../src/legacy");

const TODAY = "2026-03-01";

function inventoryWithBatches() {
  const inv = shop.createInventory();
  shop.addBatch(inv, "milk", { quantity: 10, expiresOn: "2026-03-05" });
  shop.addBatch(inv, "milk", { quantity: 20, expiresOn: "2026-03-20" });
  shop.addBatch(inv, "rice", { quantity: 50, expiresOn: "2027-01-01" });
  return inv;
}

test("dates parse, format and subtract", () => {
  assert.equal(shop.formatDate("2026-03-01"), "2026-03-01");
  assert.equal(shop.daysBetween("2026-03-01", "2026-03-05"), 4);
  assert.equal(shop.daysBetween("2026-03-05", "2026-03-01"), -4);
  assert.equal(shop.addDays("2026-02-27", 3), "2026-03-02");
});

test("an invalid date is refused", () => {
  assert.throws(() => shop.parseDate("not-a-date"), /invalid date/);
});

test("money is rounded to two places", () => {
  assert.equal(shop.round2(1.005), 1.01);
  assert.equal(shop.round2(2.34567), 2.35);
});

test("batches add up per sku", () => {
  const inv = inventoryWithBatches();
  assert.equal(shop.quantityOnHand(inv, "milk"), 30);
  assert.equal(shop.quantityOnHand(inv, "rice"), 50);
  assert.equal(shop.quantityOnHand(inv, "unknown"), 0);
});

test("a batch needs a positive quantity", () => {
  const inv = shop.createInventory();
  assert.throws(() => shop.addBatch(inv, "milk", { quantity: 0, expiresOn: "2026-03-05" }), /positive quantity/);
});

test("stock on hand ignores batches already expired on the given day", () => {
  const inv = inventoryWithBatches();
  assert.equal(shop.quantityOnHand(inv, "milk", "2026-03-10"), 20);
});

test("picking takes the batch that expires soonest first", () => {
  const inv = inventoryWithBatches();
  const picked = shop.pick(inv, "milk", 12, TODAY);
  assert.deepEqual(picked, [
    { expiresOn: "2026-03-05", quantity: 10 },
    { expiresOn: "2026-03-20", quantity: 2 },
  ]);
  assert.equal(shop.quantityOnHand(inv, "milk"), 18);
});

test("picking more than is on hand fails and says how much there is", () => {
  const inv = inventoryWithBatches();
  assert.throws(() => shop.pick(inv, "milk", 999, TODAY), /asked 999, have 30/);
});

test("picking skips batches that have expired", () => {
  const inv = inventoryWithBatches();
  const picked = shop.pick(inv, "milk", 5, "2026-03-10");
  assert.deepEqual(picked, [{ expiresOn: "2026-03-20", quantity: 5 }]);
});

test("expiring stock is listed soonest first", () => {
  const inv = inventoryWithBatches();
  const soon = shop.expiringWithin(inv, 7, TODAY);
  assert.equal(soon.length, 1);
  assert.deepEqual(soon[0], { sku: "milk", expiresOn: "2026-03-05", quantity: 10, daysLeft: 4 });
});

test("expired stock is removed and reported", () => {
  const inv = inventoryWithBatches();
  const removed = shop.removeExpired(inv, "2026-03-10");
  assert.deepEqual(removed, [{ sku: "milk", expiresOn: "2026-03-05", quantity: 10 }]);
  assert.equal(shop.quantityOnHand(inv, "milk"), 20);
});

test("the quantity discount grows with the tier", () => {
  assert.equal(shop.tierDiscountRate(1), 0);
  assert.equal(shop.tierDiscountRate(10), 0.05);
  assert.equal(shop.tierDiscountRate(50), 0.1);
  assert.equal(shop.tierDiscountRate(100), 0.15);
});

test("tax follows the category, with a fallback", () => {
  assert.equal(shop.taxRate("food"), 0.05);
  assert.equal(shop.taxRate("books"), 0);
  assert.equal(shop.taxRate("electronics"), 0.23);
  assert.equal(shop.taxRate("nonsense"), 0.23);
});

test("a line is priced with its discount and its tax", () => {
  const line = shop.priceLine({ sku: "milk", quantity: 10, unitPrice: 2, category: "food" });
  assert.equal(line.gross, 20);
  assert.equal(line.discountRate, 0.05);
  assert.equal(line.net, 19);
  assert.equal(line.tax, 0.95);
  assert.equal(line.total, 19.95);
});

test("a line without a category is taxed at the default rate", () => {
  const line = shop.priceLine({ sku: "thing", quantity: 1, unitPrice: 100 });
  assert.equal(line.category, "other");
  assert.equal(line.tax, 23);
});

test("a line needs a price and a quantity", () => {
  assert.throws(() => shop.priceLine({ sku: "x", quantity: 1 }), /unit price/);
  assert.throws(() => shop.priceLine({ sku: "x", quantity: 0, unitPrice: 1 }), /positive quantity/);
});

test("a percentage coupon takes its share", () => {
  const applied = shop.applyCoupon(100, "WELCOME", TODAY);
  assert.equal(applied.discount, 10);
  assert.match(applied.reason, /applied/);
});

test("a flat coupon never takes more than the total", () => {
  assert.equal(shop.applyCoupon(120, "FLAT15", TODAY).discount, 15);
});

test("a coupon below its minimum total is refused with a reason", () => {
  const applied = shop.applyCoupon(50, "SAVE20", TODAY);
  assert.equal(applied.discount, 0);
  assert.match(applied.reason, /at least 200/);
});

test("an expired coupon is refused", () => {
  const applied = shop.applyCoupon(100, "EXPIRED", TODAY);
  assert.equal(applied.discount, 0);
  assert.match(applied.reason, /expired/);
});

test("an unknown coupon is refused by name", () => {
  assert.match(shop.applyCoupon(100, "NOPE", TODAY).reason, /unknown coupon NOPE/);
});

test("no coupon is not an error", () => {
  assert.deepEqual(shop.applyCoupon(100, undefined, TODAY), { discount: 0, reason: "no coupon" });
});

test("an order totals its lines, its coupon and its tax", () => {
  const priced = shop.priceOrder({
    placedOn: TODAY,
    coupon: "WELCOME",
    lines: [
      { sku: "milk", quantity: 10, unitPrice: 2, category: "food" },
      { sku: "book", quantity: 2, unitPrice: 30, category: "books" },
    ],
  });
  assert.equal(priced.lines.length, 2);
  assert.equal(priced.couponDiscount, 7.9);
  assert.equal(priced.net, 71.1);
  assert.equal(priced.tax, 0.86);
  assert.equal(priced.total, 71.96);
});

test("an order without a coupon keeps the full line tax", () => {
  const priced = shop.priceOrder({
    placedOn: TODAY,
    lines: [{ sku: "tv", quantity: 1, unitPrice: 1000, category: "electronics" }],
  });
  assert.equal(priced.couponDiscount, 0);
  assert.equal(priced.net, 1000);
  assert.equal(priced.tax, 230);
  assert.equal(priced.total, 1230);
});

test("the text report names the day, the lines and the totals", () => {
  const priced = shop.priceOrder({
    placedOn: TODAY,
    lines: [{ sku: "milk", quantity: 10, unitPrice: 2, category: "food" }],
  });
  const report = shop.orderReport(priced);
  assert.match(report, /Order placed on 2026-03-01/);
  assert.match(report, /milk/);
  assert.match(report, /Total 19.95/);
});

test("the csv report has a header and one row per line", () => {
  const priced = shop.priceOrder({
    placedOn: TODAY,
    lines: [
      { sku: "milk", quantity: 10, unitPrice: 2, category: "food" },
      { sku: "book", quantity: 2, unitPrice: 30, category: "books" },
    ],
  });
  const csv = shop.orderCsv(priced).split("\n");
  assert.equal(csv[0], "sku,quantity,category,gross,discount_rate,net,tax,total");
  assert.equal(csv.length, 3);
  assert.match(csv[1], /^milk,10,food,20.00,0.05,19.00,0.95,19.95$/);
});

test("the expiry report lists what is about to go off", () => {
  const inv = inventoryWithBatches();
  assert.match(shop.expiryReport(inv, 7, TODAY), /Expiring within 7 days of 2026-03-01/);
  assert.match(shop.expiryReport(inv, 7, TODAY), /milk/);
});

test("the expiry report says so when nothing is about to go off", () => {
  const inv = shop.createInventory();
  assert.match(shop.expiryReport(inv, 7, TODAY), /Nothing expires within 7 days/);
});
