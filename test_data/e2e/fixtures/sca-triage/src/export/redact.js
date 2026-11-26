"use strict";

const redact = require("redact-fields");

function mask(value, fields) {
  if (value === null || typeof value !== "object") {
    return value;
  }
  return redact(value, fields, { marker: "[redacted]" });
}

module.exports = { mask };
