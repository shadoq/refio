"use strict";

const fs = require("fs");
const path = require("path");
const { merge } = require("deepmerge-lite");

const DEFAULTS = {
  port: 8080,
  maxRows: 50000,
  redact: ["iban", "ssn", "note"],
  csv: { delimiter: ",", quoteHeader: false },
};

function loadConfig(file) {
  const over = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, "utf8")) : {};
  return merge(DEFAULTS, over);
}

module.exports = { loadConfig, DEFAULTS };
