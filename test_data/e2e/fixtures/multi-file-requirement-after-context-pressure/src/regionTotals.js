"use strict";

// Sums order amounts (integer cents) per region. Every region that has at least one
// order appears in the result, even when its total is zero.
//
// Status rules:
//   paid      - contributes its amount
//   refunded  - contributes zero (the money went back to the customer)
function regionTotals(orders) {
  const totals = {};
  for (const order of orders) {
    if (!(order.region in totals)) totals[order.region] = 0;
    if (order.status === "refunded") continue;
    totals[order.region] += order.amount_cents;
  }
  return totals;
}

module.exports = { regionTotals };
