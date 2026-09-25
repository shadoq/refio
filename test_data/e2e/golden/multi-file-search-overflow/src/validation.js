"use strict";

// Identifier rules for records created through the public API.
const MAX_ID_LENGTH = 24;
const ID_CHARS = /^[A-Za-z0-9_]+$/;

function isValidId(value) {
  if (typeof value !== "string") return false;
  if (value.length < 1 || value.length > MAX_ID_LENGTH) return false;
  return ID_CHARS.test(value);
}

module.exports = { isValidId, MAX_ID_LENGTH };
