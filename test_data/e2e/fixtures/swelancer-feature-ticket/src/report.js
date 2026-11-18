"use strict";

function formatMoney(cents) {
  return "$" + (cents / 100).toFixed(2);
}

function renderText(orders) {
  const lines = orders.map(
    (o) => `#${o.id}  ${o.customer.padEnd(22)} ${o.status.padEnd(9)} ${formatMoney(o.total_cents).padStart(9)}`
  );
  const total = orders.reduce((sum, o) => sum + o.total_cents, 0);
  lines.push(`${orders.length} orders, total ${formatMoney(total)}`);
  return lines.join("\n") + "\n";
}

module.exports = { renderText, formatMoney };
