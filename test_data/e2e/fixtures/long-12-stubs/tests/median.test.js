'use strict';
const assert = require('node:assert/strict');
const { median } = require('../src/median');

module.exports = () => {
    assert.equal(median([3, 1, 2]), 2);
    assert.equal(median([4, 1, 3, 2]), 2.5);
    const input = [5, 1, 9];
    median(input);
    assert.deepEqual(input, [5, 1, 9]); // must not sort the caller's array in place
    assert.ok(Number.isNaN(median([])));
};
