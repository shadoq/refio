// Money formatting used by the receipt printer.
function groupThousands(whole) {
  return whole.replace(/\B(?=(\d{3})+(?!\d))/g, " ");
}

function formatMoney(amountMinor) {
  const [whole, fraction] = (amountMinor / 100).toFixed(2).split(".");
  return `${groupThousands(whole)}.${fraction} PLN`;
}

module.exports = { formatMoney };
