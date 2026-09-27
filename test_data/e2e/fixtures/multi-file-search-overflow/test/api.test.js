const test = require("node:test");
const assert = require("node:assert/strict");
const { createRecord } = require("../src/api");

const REJECTED = { ok: false, error: "INVALID_ID" };

test("a one-character identifier is accepted", () => {
  assert.deepEqual(createRecord("a"), { ok: true, id: "a" });
});

test("an identifier of exactly 24 characters is accepted", () => {
  const id = "abcdefghijklmnopqrstuvwx";
  assert.equal(id.length, 24);
  assert.deepEqual(createRecord(id), { ok: true, id });
});

test("letters, digits and underscore are all allowed", () => {
  assert.deepEqual(createRecord("Order_2024_x9"), { ok: true, id: "Order_2024_x9" });
});

test("an identifier of 25 characters is over the documented limit", () => {
  const id = "abcdefghijklmnopqrstuvwxy";
  assert.equal(id.length, 25);
  assert.deepEqual(createRecord(id), REJECTED);
});

test("an identifier of 32 characters is over the documented limit", () => {
  const id = "abcdefghijklmnopqrstuvwxyz012345";
  assert.equal(id.length, 32);
  assert.deepEqual(createRecord(id), REJECTED);
});

test("an empty identifier is rejected", () => {
  assert.deepEqual(createRecord(""), REJECTED);
});

test("an identifier containing a space is rejected", () => {
  assert.deepEqual(createRecord("order 1"), REJECTED);
});

test("an identifier with a non-ASCII letter is rejected", () => {
  assert.deepEqual(createRecord("café"), REJECTED);
});

test("null is rejected", () => {
  assert.deepEqual(createRecord(null), REJECTED);
});

test("a number is rejected even when its digits would be valid", () => {
  assert.deepEqual(createRecord(12345), REJECTED);
});
