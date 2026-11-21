const test = require('node:test');
const assert = require('node:assert/strict');
const { formatRanges } = require('./ranges');

test('empty input gives an empty string', () => {
  assert.equal(formatRanges([]), '');
});

test('a single number', () => {
  assert.equal(formatRanges([4]), '4');
});

test('a run of three or more becomes a range', () => {
  assert.equal(formatRanges([1, 2, 3]), '1..3');
  assert.equal(formatRanges([10, 11, 12, 13, 14]), '10..14');
});

test('numbers that are not consecutive stay separate', () => {
  assert.equal(formatRanges([1, 3, 5]), '1,3,5');
});

test('a run of two is written number by number', () => {
  assert.equal(formatRanges([7, 8]), '7,8');
  assert.equal(formatRanges([1, 2, 4, 5, 6, 9, 10]), '1,2,4..6,9,10');
});

test('mixed runs and singles', () => {
  assert.equal(formatRanges([1, 2, 3, 5, 7, 8, 9]), '1..3,5,7..9');
});

test('input is sorted first', () => {
  assert.equal(formatRanges([5, 3, 4, 1]), '1,3..5');
  assert.equal(formatRanges([9, 7, 8, 1]), '1,7..9');
});

test('duplicates are removed first', () => {
  assert.equal(formatRanges([1, 1, 2, 3, 3]), '1..3');
  assert.equal(formatRanges([2, 2, 3, 3]), '2,3');
  assert.equal(formatRanges([6, 6, 6]), '6');
});

test('negative numbers and zero', () => {
  assert.equal(formatRanges([-3, -2, -1, 0, 1]), '-3..1');
  assert.equal(formatRanges([-5, -3, -2, -1]), '-5,-3..-1');
  assert.equal(formatRanges([-2, -1]), '-2,-1');
});

test('the input array is not modified', () => {
  const input = [3, 1, 2, 2];
  formatRanges(input);
  assert.deepEqual(input, [3, 1, 2, 2]);
});

test('values that are not integers are rejected', () => {
  for (const bad of [1.5, '2', NaN, Infinity, null, undefined]) {
    assert.throws(() => formatRanges([1, bad, 3]), TypeError, `accepted ${String(bad)}`);
  }
});

test('a non-array argument is rejected', () => {
  assert.throws(() => formatRanges('123'), TypeError);
  assert.throws(() => formatRanges(undefined), TypeError);
});
