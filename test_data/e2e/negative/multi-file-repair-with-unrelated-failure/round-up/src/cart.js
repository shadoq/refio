"use strict";

// All amounts are integer cents.
const BULK_DISCOUNT_MIN_QUANTITY = 10;

// Price of one cart line. Bulk lines get 10% off; the discounted price is rounded down.
function lineTotal(unitPriceCents, quantity) {
  const gross = unitPriceCents * quantity;
  if (quantity >= BULK_DISCOUNT_MIN_QUANTITY) {
    return Math.ceil((gross * 9) / 10);
  }
  return gross;
}

// Tax is added on top of the net amount; the tax amount is rounded down.
function withTax(netCents, taxRatePercent) {
  return netCents + Math.floor((netCents * taxRatePercent) / 100);
}

function orderTotal(lines, taxRatePercent) {
  const net = lines.reduce((sum, l) => sum + lineTotal(l.unitPriceCents, l.quantity), 0);
  return withTax(net, taxRatePercent);
}

module.exports = { lineTotal, withTax, orderTotal };
