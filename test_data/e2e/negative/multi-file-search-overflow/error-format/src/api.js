"use strict";

const { isValidId } = require("./validation");

function createRecord(id) {
  if (!isValidId(id)) {
    return { ok: false, error: { code: "INVALID_ID", message: "identifier must be 1-24 characters" } };
  }
  return { ok: true, id };
}

module.exports = { createRecord };
