"use strict";

const STATUSES = ["pending", "paid", "shipped", "refunded"];

function formatMoney(cents) {
  return "$" + (cents / 100).toFixed(2);
}

function renderText(orders) {
  const lines = orders.map(
    (o) => `#${o.id}  ${o.customer.padEnd(22)} ${o.status.padEnd(9)} ${formatMoney(o.total_cents).padStart(9)}`
  );
  const total = orders.reduce((sum, o) => sum + o.total_cents, 0);
  lines.unshift("ID     CUSTOMER               STATUS    TOTAL");
  lines.push(`${orders.length} orders, total ${formatMoney(total)}`);
  return lines.join("\n") + "\n";
}

function csvField(value) {
  const text = String(value);
  return /[",\r\n]/.test(text) ? '"' + text.replace(/"/g, '""') + '"' : text;
}

function renderCsv(orders) {
  const sorted = [...orders].sort((a, b) =>
    a.placed_on < b.placed_on ? -1 : a.placed_on > b.placed_on ? 1 : a.id - b.id
  );
  const lines = ["id,customer,status,total,placed_on"];
  for (const o of sorted) {
    lines.push([o.id, o.customer, o.status, (o.total_cents / 100).toFixed(2), o.placed_on].map(csvField).join(","));
  }
  return lines.join("\n") + "\n";
}

function parseStatuses(value) {
  const wanted = new Set();
  for (const raw of value.split(",")) {
    const name = raw.trim().toLowerCase();
    if (!STATUSES.includes(name)) throw new Error(`Unknown status: ${raw.trim()}`);
    wanted.add(name);
  }
  return wanted;
}

function parseSince(value) {
  const ok = /^\d{4}-\d{2}-\d{2}$/.test(value) && !Number.isNaN(Date.parse(value + "T00:00:00Z"));
  if (!ok) throw new Error(`Invalid date: ${value}`);
  return value;
}

function render(orders, options = {}) {
  const format = options.format === undefined ? "text" : options.format;
  if (format !== "text" && format !== "csv") throw new Error(`Unknown format: ${format}`);
  let rows = orders;
  if (options.status !== undefined) {
    const wanted = parseStatuses(options.status);
    rows = rows.filter((o) => wanted.has(o.status));
  }
  if (options.since !== undefined) {
    const since = parseSince(options.since);
    rows = rows.filter((o) => o.placed_on >= since);
  }
  return format === "csv" ? renderCsv(rows) : renderText(rows);
}

module.exports = { render, renderText, formatMoney };
