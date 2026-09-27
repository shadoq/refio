// Superseded by ./format.js and kept only until the export report is retired.
function groupThousands(whole) {
  return whole.replace(/\B(?=(\d{3})+(?!\d))/g, " ");
}

function formatMoney(amountMinor) {
  const [whole, fraction] = (amountMinor / 100).toFixed(2).split(".");
  return `${groupThousands(whole)}.${fraction} PLN`;
}

module.exports = { formatMoney };
