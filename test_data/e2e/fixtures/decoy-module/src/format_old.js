// Superseded by ./format.js and kept only until the export report is retired.
// Nothing in this package requires it.
function formatMoney(amountMinor) {
  return `${(amountMinor / 100).toFixed(2)} zl`;
}

module.exports = { formatMoney };
