// Turning a priced order or a stock list into something a person reads: the text
// report, the CSV export and the expiry list.
const { formatDate } = require("./dates");
const { expiringWithin } = require("./inventory");

function orderReport(priced) {
  const rows = priced.lines.map(
    (line) =>
      `${line.sku.padEnd(10)} ${String(line.quantity).padStart(4)} x  net ${line.net.toFixed(2).padStart(9)}  tax ${line.tax.toFixed(2).padStart(8)}`,
  );
  return [
    `Order placed on ${priced.placedOn}`,
    ...rows,
    `Coupon: ${priced.couponReason} (-${priced.couponDiscount.toFixed(2)})`,
    `Net ${priced.net.toFixed(2)}  Tax ${priced.tax.toFixed(2)}  Total ${priced.total.toFixed(2)}`,
  ].join("\n");
}

function orderCsv(priced) {
  const header = "sku,quantity,category,gross,discount_rate,net,tax,total";
  const rows = priced.lines.map(
    (line) =>
      [line.sku, line.quantity, line.category, line.gross.toFixed(2), line.discountRate.toFixed(2), line.net.toFixed(2), line.tax.toFixed(2), line.total.toFixed(2)].join(","),
  );
  return [header, ...rows].join("\n");
}

function expiryReport(inventory, days, onDate) {
  const soon = expiringWithin(inventory, days, onDate);
  if (soon.length === 0) return `Nothing expires within ${days} days of ${formatDate(onDate)}`;
  return [
    `Expiring within ${days} days of ${formatDate(onDate)}`,
    ...soon.map((row) => `${row.sku.padEnd(10)} ${String(row.quantity).padStart(4)} on ${row.expiresOn} (${row.daysLeft}d)`),
  ].join("\n");
}

module.exports = { orderReport, orderCsv, expiryReport };
