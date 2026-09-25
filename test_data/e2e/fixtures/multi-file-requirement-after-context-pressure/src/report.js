"use strict";

const { regionTotals } = require("./regionTotals");
const { overallTotal } = require("./overallTotal");

// CSV contract used by the legacy finance client:
//   - separator is a semicolon
//   - header is exactly "region;total_cents"
//   - one row per region, regions sorted alphabetically
//   - a final "TOTAL;<cents>" row
//   - every line, including the last, ends with LF
const SEPARATOR = ";";
const HEADER = ["region", "total_cents"];

function buildReport(orders) {
  const totals = regionTotals(orders);
  const regions = Object.keys(totals).sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
  const lines = [HEADER.join(SEPARATOR)];
  for (const region of regions) lines.push([region, totals[region]].join(SEPARATOR));
  lines.push(["TOTAL", overallTotal(orders)].join(SEPARATOR));
  return lines.join("\n") + "\n";
}

// Parses the order export: a header line "order_id;region;status;amount_cents"
// followed by one order per line. Blank lines are ignored.
function parseOrders(text) {
  const lines = text.split(/\r?\n/).filter((l) => l.trim() !== "");
  return lines.slice(1).map((line) => {
    const [order_id, region, status, amount] = line.split(SEPARATOR);
    return { order_id, region, status, amount_cents: Number.parseInt(amount, 10) };
  });
}

module.exports = { buildReport, parseOrders };
