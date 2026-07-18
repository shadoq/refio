// @app/web depends on both @app/api and @app/core.
const { priceLabel } = require("@app/api");
const { formatMoney } = require("@app/core");

function render(cents) {
  return `${priceLabel(cents)} (${formatMoney(cents)})`;
}

module.exports = { render };
