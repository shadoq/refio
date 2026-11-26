"use strict";

const { stringify } = require("tabular-csv");
const { plural } = require("../format/labels");
const { mask } = require("./redact");

function header(columns, count) {
  return columns.concat([plural("row", count)]).join(",");
}

async function exportCsv(query, config) {
  const rows = await rowsFor(query, config);
  const columns = ["account", "iban", "amount", "currency"];
  const body = rows.map((row) => mask(row, config.redact));
  return stringify([header(columns, rows.length)].concat(body), { delimiter: config.csv.delimiter });
}

// Rows come from the ledger service; the shape is fixed by its OpenAPI document.
async function rowsFor() {
  throw new Error("ledger client not configured in this checkout");
}

module.exports = { exportCsv, header };
