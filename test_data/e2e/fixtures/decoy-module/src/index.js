const { formatMoney } = require("./format");

function receiptLine(label, amountMinor) {
  return `${label}: ${formatMoney(amountMinor)}`;
}

module.exports = { receiptLine };
