'use strict';
const assert = require('node:assert/strict');
const { chunk } = require('../src/chunk');

module.exports = () => {
    assert.deepEqual(chunk([1, 2, 3, 4, 5], 2), [[1, 2], [3, 4], [5]]);
    assert.deepEqual(chunk([1, 2, 3], 3), [[1, 2, 3]]);
    assert.deepEqual(chunk([], 4), []);
    assert.throws(() => chunk([1], 0), RangeError);
    assert.throws(() => chunk([1], 1.5), RangeError);
};
