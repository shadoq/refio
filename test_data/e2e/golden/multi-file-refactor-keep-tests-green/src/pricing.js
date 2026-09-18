// What an order costs: the quantity tiers, the per-category tax, the coupons and the
// order total built from them.
const { daysBetween, formatDate, round2 } = require("./dates");

const TAX_BY_CATEGORY = {
  food: 0.05,
  books: 0.0,
  electronics: 0.23,
  clothing: 0.23,
  other: 0.23,
};

const TIER_DISCOUNTS = [
  { minQuantity: 100, rate: 0.15 },
  { minQuantity: 50, rate: 0.1 },
  { minQuantity: 10, rate: 0.05 },
];

const COUPONS = {
  WELCOME: { kind: "percent", value: 0.1, minTotal: 0 },
  SAVE20: { kind: "percent", value: 0.2, minTotal: 200 },
  FLAT15: { kind: "flat", value: 15, minTotal: 100 },
  EXPIRED: { kind: "percent", value: 0.5, minTotal: 0, expiresOn: "2020-01-01" },
};

function tierDiscountRate(quantity) {
  for (const tier of TIER_DISCOUNTS) {
    if (quantity >= tier.minQuantity) return tier.rate;
  }
  return 0;
}

function taxRate(category) {
  return TAX_BY_CATEGORY[category] ?? TAX_BY_CATEGORY.other;
}

function priceLine(line) {
  if (typeof line.unitPrice !== "number" || line.unitPrice < 0) {
    throw new Error(`line ${line.sku} needs a unit price`);
  }
  if (typeof line.quantity !== "number" || line.quantity <= 0) {
    throw new Error(`line ${line.sku} needs a positive quantity`);
  }
  const gross = line.unitPrice * line.quantity;
  const discountRate = tierDiscountRate(line.quantity);
  const discounted = gross * (1 - discountRate);
  const tax = discounted * taxRate(line.category);
  return {
    sku: line.sku,
    quantity: line.quantity,
    category: line.category ?? "other",
    gross: round2(gross),
    discountRate,
    net: round2(discounted),
    tax: round2(tax),
    total: round2(discounted + tax),
  };
}

function applyCoupon(netTotal, code, onDate) {
  if (!code) return { discount: 0, reason: "no coupon" };
  const coupon = COUPONS[code];
  if (!coupon) return { discount: 0, reason: `unknown coupon ${code}` };
  if (coupon.expiresOn && daysBetween(onDate, coupon.expiresOn) < 0) {
    return { discount: 0, reason: `coupon ${code} expired` };
  }
  if (netTotal < coupon.minTotal) {
    return { discount: 0, reason: `coupon ${code} needs a total of at least ${coupon.minTotal}` };
  }
  const discount = coupon.kind === "percent" ? netTotal * coupon.value : Math.min(coupon.value, netTotal);
  return { discount: round2(discount), reason: `coupon ${code} applied` };
}

function priceOrder(order) {
  const lines = order.lines.map(priceLine);
  const net = round2(lines.reduce((sum, line) => sum + line.net, 0));
  const coupon = applyCoupon(net, order.coupon, order.placedOn);
  const netAfterCoupon = round2(net - coupon.discount);
  // The coupon lowers the taxable amount, so the tax is recomputed proportionally
  // rather than carried over from the lines.
  const share = net === 0 ? 0 : netAfterCoupon / net;
  const tax = round2(lines.reduce((sum, line) => sum + line.tax, 0) * share);
  return {
    placedOn: formatDate(order.placedOn),
    lines,
    net: netAfterCoupon,
    couponDiscount: coupon.discount,
    couponReason: coupon.reason,
    tax,
    total: round2(netAfterCoupon + tax),
  };
}

module.exports = {
  TAX_BY_CATEGORY,
  TIER_DISCOUNTS,
  COUPONS,
  tierDiscountRate,
  taxRate,
  priceLine,
  applyCoupon,
  priceOrder,
};
