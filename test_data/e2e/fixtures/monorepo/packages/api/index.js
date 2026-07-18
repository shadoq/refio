// @app/api depends on @app/core for money formatting.
const { formatMoney } = require("@app/core");

function priceLabel(cents) {
  return `Price: ${formatMoney(cents)}`;
}

module.exports = { priceLabel };
