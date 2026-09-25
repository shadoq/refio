"use strict";

const { isValidId } = require("./validation");

function createRecord(id) {
  if (!isValidId(id)) {
    return { ok: false, error: "INVALID_ID" };
  }
  return { ok: true, id };
}

module.exports = { createRecord };
