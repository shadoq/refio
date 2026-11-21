"use strict";

const fs = require("node:fs");

function loadOrders(file) {
  return JSON.parse(fs.readFileSync(file, "utf8"));
}

module.exports = { loadOrders };
