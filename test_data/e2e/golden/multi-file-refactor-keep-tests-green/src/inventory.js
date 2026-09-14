// Stock kept as dated batches: what is on hand, what leaves first and what has gone
// off. It knows nothing about prices or reports.
const { formatDate, daysBetween } = require("./dates");

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

module.exports = {
  createInventory,
  addBatch,
  quantityOnHand,
  pick,
  expiringWithin,
  removeExpired,
};
