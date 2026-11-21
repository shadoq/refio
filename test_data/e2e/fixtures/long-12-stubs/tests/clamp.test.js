'use strict';
const assert = require('node:assert/strict');
const { clamp } = require('../src/clamp');

module.exports = () => {
    assert.equal(clamp(5, 0, 10), 5);
    assert.equal(clamp(-3, 0, 10), 0);
    assert.equal(clamp(42, 0, 10), 10);
    // Swapped bounds are normalised, not rejected.
    assert.equal(clamp(42, 10, 0), 10);
    assert.ok(Number.isNaN(clamp(NaN, 0, 10)));
};
