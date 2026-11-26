"use strict";

const { parse } = require("query-parse");

// The front end sends nested filters as `account[id]=12&amount[gte]=100`.
function queryParameters(search) {
  const parsed = parse(search, { depth: 2 });
  return parsed && typeof parsed === "object" ? parsed : {};
}

module.exports = { parse: queryParameters };
