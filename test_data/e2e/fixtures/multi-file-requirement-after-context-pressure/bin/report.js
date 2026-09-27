#!/usr/bin/env node
"use strict";

// Prints the sales report for an order export: node bin/report.js <orders.csv>
const fs = require("node:fs");
const { buildReport, parseOrders } = require("../src/report");

const input = process.argv[2];
if (!input) {
  console.error("usage: node bin/report.js <orders.csv>");
  process.exit(2);
}
process.stdout.write(buildReport(parseOrders(fs.readFileSync(input, "utf8"))));
