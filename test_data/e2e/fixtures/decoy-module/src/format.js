// Money formatting used by the receipt printer.
function formatMoney(amountMinor) {
  return `${(amountMinor / 100).toFixed(2)} PLN`;
}

module.exports = { formatMoney };
