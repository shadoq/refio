#!/usr/bin/env node
"use strict";

const path = require("node:path");
const { loadOrders } = require("./orders");
const { render } = require("./report");

function parseOptions(args) {
  const options = {};
  for (let i = 0; i < args.length; i += 2) {
    const flag = args[i];
    const value = args[i + 1];
    if (!["--format", "--status", "--since"].includes(flag) || value === undefined) {
      throw new Error(`Bad option: ${flag}`);
    }
    options[flag.slice(2)] = value;
  }
  return options;
}

function main(argv) {
  const [command, ...rest] = argv;
  if (command !== "report") {
    process.stderr.write("Usage: cli.js report [--format text|csv] [--status a,b] [--since YYYY-MM-DD]\n");
    return 2;
  }
  try {
    const options = parseOptions(rest);
    const orders = loadOrders(path.join(__dirname, "..", "data", "orders.json"));
    process.stdout.write(render(orders, options));
    return 0;
  } catch (err) {
    process.stderr.write(err.message + "\n");
    return 2;
  }
}

process.exitCode = main(process.argv.slice(2));
