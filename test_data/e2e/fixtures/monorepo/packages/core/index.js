// @app/core: shared domain primitives. Depends on nothing else in the monorepo.
function formatMoney(cents) {
  return `$${(cents / 100).toFixed(2)}`;
}

module.exports = { formatMoney };
