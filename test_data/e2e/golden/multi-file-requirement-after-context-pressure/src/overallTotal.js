"use strict";

// Grand total of all orders in integer cents. Kept separate from the regional sums
// because finance reconciles the two independently.
function overallTotal(orders) {
  let total = 0;
  for (const order of orders) {
    switch (order.status) {
      case "refunded":
      case "canceled":
        break;
      default:
        total += order.amount_cents;
    }
  }
  return total;
}

module.exports = { overallTotal };
