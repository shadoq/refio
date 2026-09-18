// The shop module. Inventory with batches and expiry dates, pricing with tiered
// discounts, coupons and per-category tax, and reporting in text and CSV. Everything
// lives here, which is why it is hard to change.

const MS_PER_DAY = 24 * 60 * 60 * 1000;

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

function parseDate(value) {
  if (value instanceof Date) return new Date(value.getTime());
  const parsed = new Date(`${value}T00:00:00.000Z`);
  if (Number.isNaN(parsed.getTime())) throw new Error(`invalid date: ${value}`);
  return parsed;
}

function formatDate(date) {
  return parseDate(date).toISOString().slice(0, 10);
}

function daysBetween(from, to) {
  return Math.round((parseDate(to).getTime() - parseDate(from).getTime()) / MS_PER_DAY);
}

function addDays(date, days) {
  return formatDate(new Date(parseDate(date).getTime() + days * MS_PER_DAY));
}

function round2(value) {
  return Math.round((value + Number.EPSILON) * 100) / 100;
}

// --- inventory ---------------------------------------------------------------

function createInventory() {
  return { items: {} };
}

function addBatch(inventory, sku, batch) {
  if (!batch || typeof batch.quantity !== "number" || batch.quantity <= 0) {
    throw new Error(`batch for ${sku} needs a positive quantity`);
  }
  const expiresOn = formatDate(batch.expiresOn);
  const item = inventory.items[sku] || (inventory.items[sku] = { sku, batches: [] });
  item.batches.push({ quantity: batch.quantity, expiresOn, receivedOn: formatDate(batch.receivedOn ?? batch.expiresOn) });
  item.batches.sort((a, b) => (a.expiresOn < b.expiresOn ? -1 : a.expiresOn > b.expiresOn ? 1 : 0));
  return inventory;
}

function quantityOnHand(inventory, sku, onDate) {
  const item = inventory.items[sku];
  if (!item) return 0;
  let total = 0;
  for (const batch of item.batches) {
    if (onDate === undefined || daysBetween(onDate, batch.expiresOn) >= 0) total += batch.quantity;
  }
  return total;
}

// Oldest batch first, so stock that expires soonest leaves first.
function pick(inventory, sku, quantity, onDate) {
  const item = inventory.items[sku];
  const available = quantityOnHand(inventory, sku, onDate);
  if (available < quantity) {
    throw new Error(`not enough ${sku}: asked ${quantity}, have ${available}`);
  }
  let left = quantity;
  const picked = [];
  for (const batch of item.batches) {
    if (left === 0) break;
    if (onDate !== undefined && daysBetween(onDate, batch.expiresOn) < 0) continue;
    const take = Math.min(batch.quantity, left);
    batch.quantity -= take;
    left -= take;
    picked.push({ expiresOn: batch.expiresOn, quantity: take });
  }
  item.batches = item.batches.filter((b) => b.quantity > 0);
  return picked;
}

function expiringWithin(inventory, days, onDate) {
  const soon = [];
  for (const item of Object.values(inventory.items)) {
    for (const batch of item.batches) {
      const left = daysBetween(onDate, batch.expiresOn);
      if (left >= 0 && left <= days) {
        soon.push({ sku: item.sku, expiresOn: batch.expiresOn, quantity: batch.quantity, daysLeft: left });
      }
    }
  }
  soon.sort((a, b) => (a.expiresOn < b.expiresOn ? -1 : a.expiresOn > b.expiresOn ? 1 : a.sku.localeCompare(b.sku)));
  return soon;
}

function removeExpired(inventory, onDate) {
  const removed = [];
  for (const item of Object.values(inventory.items)) {
    const kept = [];
    for (const batch of item.batches) {
      if (daysBetween(onDate, batch.expiresOn) < 0) {
        removed.push({ sku: item.sku, expiresOn: batch.expiresOn, quantity: batch.quantity });
      } else {
        kept.push(batch);
      }
    }
    item.batches = kept;
  }
  return removed;
}

// --- pricing -----------------------------------------------------------------

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

// --- reporting ---------------------------------------------------------------

function orderReport(priced) {
  const rows = priced.lines.map(
    (line) =>
      `${line.sku.padEnd(10)} ${String(line.quantity).padStart(4)} x  net ${line.net.toFixed(2).padStart(9)}  tax ${line.tax.toFixed(2).padStart(8)}`,
  );
  return [
    `Order placed on ${priced.placedOn}`,
    ...rows,
    `Coupon: ${priced.couponReason} (-${priced.couponDiscount.toFixed(2)})`,
    `Net ${priced.net.toFixed(2)}  Tax ${priced.tax.toFixed(2)}  Total ${priced.total.toFixed(2)}`,
  ].join("\n");
}

function orderCsv(priced) {
  const header = "sku,quantity,category,gross,discount_rate,net,tax,total";
  const rows = priced.lines.map(
    (line) =>
      [line.sku, line.quantity, line.category, line.gross.toFixed(2), line.discountRate.toFixed(2), line.net.toFixed(2), line.tax.toFixed(2), line.total.toFixed(2)].join(","),
  );
  return [header, ...rows].join("\n");
}

function expiryReport(inventory, days, onDate) {
  const soon = expiringWithin(inventory, days, onDate);
  if (soon.length === 0) return `Nothing expires within ${days} days of ${formatDate(onDate)}`;
  return [
    `Expiring within ${days} days of ${formatDate(onDate)}`,
    ...soon.map((row) => `${row.sku.padEnd(10)} ${String(row.quantity).padStart(4)} on ${row.expiresOn} (${row.daysLeft}d)`),
  ].join("\n");
}

module.exports = {
  MS_PER_DAY,
  TAX_BY_CATEGORY,
  TIER_DISCOUNTS,
  COUPONS,
  parseDate,
  formatDate,
  daysBetween,
  addDays,
  round2,
  createInventory,
  addBatch,
  quantityOnHand,
  pick,
  expiringWithin,
  removeExpired,
  tierDiscountRate,
  taxRate,
  priceLine,
  applyCoupon,
  priceOrder,
  orderReport,
  orderCsv,
  expiryReport,
};
