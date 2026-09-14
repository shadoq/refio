// The shop module, kept as the entry point every caller already uses. The work now
// lives in four modules behind it: dates and money, inventory, pricing and reporting.
// Re-exporting from here keeps the public surface exactly as it was.
const dates = require("./dates");
const inventory = require("./inventory");
const pricing = require("./pricing");
const report = require("./report");

module.exports = {
  MS_PER_DAY: dates.MS_PER_DAY,
  TAX_BY_CATEGORY: pricing.TAX_BY_CATEGORY,
  TIER_DISCOUNTS: pricing.TIER_DISCOUNTS,
  COUPONS: pricing.COUPONS,
  parseDate: dates.parseDate,
  formatDate: dates.formatDate,
  daysBetween: dates.daysBetween,
  addDays: dates.addDays,
  round2: dates.round2,
  createInventory: inventory.createInventory,
  addBatch: inventory.addBatch,
  quantityOnHand: inventory.quantityOnHand,
  pick: inventory.pick,
  expiringWithin: inventory.expiringWithin,
  removeExpired: inventory.removeExpired,
  tierDiscountRate: pricing.tierDiscountRate,
  taxRate: pricing.taxRate,
  priceLine: pricing.priceLine,
  applyCoupon: pricing.applyCoupon,
  priceOrder: pricing.priceOrder,
  orderReport: report.orderReport,
  orderCsv: report.orderCsv,
  expiryReport: report.expiryReport,
};
