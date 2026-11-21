const test = require("node:test");
const assert = require("node:assert/strict");
const ts = require("./textstats.js");

test("word_freq counts case-insensitively, strips punctuation, ties alphabetical", () => {
  const text = "The cat and the hat. The END, and (the) cat!";
  assert.deepEqual(ts.word_freq(text, 3), [["the", 4], ["and", 2], ["cat", 2]]);
  assert.deepEqual(ts.word_freq(text).length, 5);
  assert.deepEqual(ts.word_freq(""), []);
  assert.deepEqual(ts.word_freq("   ...  !! "), []);
});

test("median handles odd, even and empty input without touching the argument", () => {
  const odd = [5, 1, 3];
  assert.equal(ts.median(odd), 3);
  assert.deepEqual(odd, [5, 1, 3]);
  assert.equal(ts.median([4, 1, 3, 2]), 2.5);
  assert.equal(ts.median([10, 2, 3, 3, 9, 1, 100, 7, 7, 7]), 7);
  assert.throws(() => ts.median([]), { name: "Error", message: "median of empty list" });
});

test("round_half_even sends halves to the even neighbour and never returns negative zero", () => {
  const cases = [[0.5, 0], [1.5, 2], [2.5, 2], [3.5, 4], [-0.5, 0], [-1.5, -2], [-2.5, -2], [2.4, 2], [-2.6, -3]];
  for (const [input, expected] of cases) {
    assert.equal(ts.round_half_even(input), expected, "round_half_even(" + input + ")");
  }
});

test("wrap is greedy and keeps over-long words on their own line", () => {
  assert.deepEqual(ts.wrap("aaa bb c dddddddd ee", 6), ["aaa bb", "c", "dddddddd", "ee"]);
  assert.deepEqual(ts.wrap("  spaced   out\twords \n here ", 11), ["spaced out", "words here"]);
  assert.deepEqual(ts.wrap("", 10), []);
});

test("chunk splits into pieces and rejects a non-positive size", () => {
  assert.deepEqual(ts.chunk([1, 2, 3, 4, 5], 2), [[1, 2], [3, 4], [5]]);
  assert.deepEqual(ts.chunk([], 3), []);
  assert.deepEqual(ts.chunk([1, 2], 5), [[1, 2]]);
  assert.throws(() => ts.chunk([1], 0), { message: "size must be positive" });
  assert.throws(() => ts.chunk([1], -2), { message: "size must be positive" });
});

test("title_case follows the Python rule: a letter after any non-letter is upper-cased", () => {
  assert.equal(ts.title_case("hello wORLD-foo bar's x1y"), "Hello World-Foo Bar'S X1Y");
  assert.equal(ts.title_case(""), "");
  assert.equal(ts.title_case("ALREADY LOUD"), "Already Loud");
});

test("ring_index is never negative for a positive ring size", () => {
  assert.deepEqual([-1, -6, 0, 7].map((i) => ts.ring_index(i, 5)), [4, 4, 0, 2]);
});

test("half_of rounds towards negative infinity", () => {
  assert.deepEqual([7, -7, -8, 0].map((x) => ts.half_of(x)), [3, -4, -4, 0]);
});
