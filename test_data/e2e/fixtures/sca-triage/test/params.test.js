"use strict";

const test = require("node:test");
const assert = require("node:assert");
const { parse } = require("../src/http/params");

test("nested filters become objects", () => {
  assert.deepStrictEqual(parse("account[id]=12&amount[gte]=100"), {
    account: { id: "12" },
    amount: { gte: "100" },
  });
});

test("an empty query is an empty object", () => {
  assert.deepStrictEqual(parse(""), {});
});
