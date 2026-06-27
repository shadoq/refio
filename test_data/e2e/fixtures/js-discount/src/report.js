const { applyDiscount } = require("./discount");

// Clearance also currently uses the regular 10% discount.
function clearance(price) {
  return applyDiscount(price);
}

module.exports = { clearance };
