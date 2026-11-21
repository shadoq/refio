#!/usr/bin/env node
"use strict";

const path = require("node:path");
const { loadOrders } = require("./orders");
const { renderText } = require("./report");

function main(argv) {
  const [command] = argv;
  if (command !== "report") {
    process.stderr.write("Usage: cli.js report\n");
    return 2;
  }
  const orders = loadOrders(path.join(__dirname, "..", "data", "orders.json"));
  process.stdout.write(renderText(orders));
  return 0;
}

process.exitCode = main(process.argv.slice(2));
