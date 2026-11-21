import { test } from "node:test";
import assert from "node:assert/strict";
import { split } from "../src/split.ts";

test("equal ratios hand leftover cents to the earliest parties", () => {
  assert.deepStrictEqual(split(100, [1, 1, 1]), [34, 33, 33]);
  assert.deepStrictEqual(split(5, [1, 1]), [3, 2]);
  assert.deepStrictEqual(split(1, [1, 1, 1]), [1, 0, 0]);
  assert.deepStrictEqual(split(2, [1, 1, 1]), [1, 1, 0]);
});

test("leftover goes to the largest fractional remainder, not to the first party", () => {
  assert.deepStrictEqual(split(10, [1, 2]), [3, 7]);
  assert.deepStrictEqual(split(100, [1, 2, 3]), [17, 33, 50]);
  assert.deepStrictEqual(split(7, [5, 3, 1]), [4, 2, 1]);
});

test("ties on the remainder go to the earlier index", () => {
  assert.deepStrictEqual(split(2, [1, 1, 2]), [1, 0, 1]);
  assert.deepStrictEqual(split(3, [2, 2, 2, 2]), [1, 1, 1, 0]);
});

test("exact divisions have no leftover", () => {
  assert.deepStrictEqual(split(10, [1, 2, 3, 4]), [1, 2, 3, 4]);
  assert.deepStrictEqual(split(0, [1, 2]), [0, 0]);
  assert.deepStrictEqual(split(42, [7]), [42]);
});

test("ratios are relative, so scaling them changes nothing", () => {
  assert.deepStrictEqual(split(101, [10, 20, 30]), split(101, [1, 2, 3]));
});

test("negative amounts mirror the positive result", () => {
  assert.deepStrictEqual(split(-100, [1, 1, 1]), [-34, -33, -33]);
  assert.deepStrictEqual(split(-10, [1, 2]), [-3, -7]);
  assert.deepStrictEqual(split(-100, [1, 2, 3]), [-17, -33, -50]);
});

test("a party that gets nothing is plain zero, never negative zero", () => {
  assert.deepStrictEqual(split(-1, [1, 1, 1]), [-1, 0, 0]);
  assert.deepStrictEqual(split(0, [3, 4]), [0, 0]);
  assert.deepStrictEqual(split(-2, [1, 1, 1]), [-1, -1, 0]);
});

test("result always sums to the amount", () => {
  for (const amount of [-1001, -7, 0, 1, 99, 100, 12345]) {
    for (const ratios of [[1, 1], [3, 1, 1], [5, 7, 11, 13], [1, 1, 1, 1, 1, 1, 1]]) {
      const parts = split(amount, ratios);
      assert.equal(parts.length, ratios.length);
      assert.equal(parts.reduce((a, b) => a + b, 0), amount);
    }
  }
});

test("exact for amounts and ratios near the safe integer limit", () => {
  assert.deepStrictEqual(split(9007199254740991, [1, 2, 3]), [1501199875790165,3002399751580330,4503599627370496]);
  assert.deepStrictEqual(split(-9007199254740991, [1, 2, 3]), [-1501199875790165,-3002399751580330,-4503599627370496]);
  assert.deepStrictEqual(split(1, [9007199254740991, 9007199254740991]), [1,0]);
});

test("does not modify the ratios array", () => {
  const ratios = [3, 1, 2];
  split(100, ratios);
  assert.deepStrictEqual(ratios, [3, 1, 2]);
});

test("rejects bad ratios", () => {
  assert.throws(() => split(100, []), RangeError);
  assert.throws(() => split(100, [1, 0]), RangeError);
  assert.throws(() => split(100, [1, -1]), RangeError);
  assert.throws(() => split(100, [1, 1.5]), RangeError);
  assert.throws(() => split(100, [1, NaN]), RangeError);
  assert.throws(() => split(100, [1, Infinity]), RangeError);
});

test("rejects bad amounts", () => {
  assert.throws(() => split(10.5, [1, 1]), RangeError);
  assert.throws(() => split(NaN, [1, 1]), RangeError);
  assert.throws(() => split(Infinity, [1, 1]), RangeError);
  assert.throws(() => split(Number.MAX_SAFE_INTEGER + 1, [1, 1]), RangeError);
});
