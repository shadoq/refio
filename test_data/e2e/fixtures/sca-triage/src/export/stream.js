"use strict";

const { Readable } = require("stream");
const { mask } = require("./redact");

function exportJson(query, config) {
  const seen = [];
  const source = new Readable({ read() {} });
  source.push(JSON.stringify({ query: mask(query, config.redact), seen }) + "\n");
  source.push(null);
  return source;
}

module.exports = { exportJson };
