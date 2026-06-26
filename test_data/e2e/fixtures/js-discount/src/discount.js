// Applies a fixed 10% discount. We want the rate to be configurable per caller.
function applyDiscount(price) {
  return Math.round(price * (1 - 0.1));
}

module.exports = { applyDiscount };
