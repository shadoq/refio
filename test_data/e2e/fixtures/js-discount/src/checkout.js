const { applyDiscount } = require("./discount");

// Standard checkout uses the regular 10% discount.
function checkout(cart) {
  return cart.map((price) => applyDiscount(price));
}

module.exports = { checkout };
