'use strict';
const assert = require('node:assert/strict');
const { wrap } = require('../src/wrap');

module.exports = () => {
    assert.deepEqual(wrap('the quick brown fox jumps', 10), ['the quick', 'brown fox', 'jumps']);
    assert.deepEqual(wrap('supercalifragilistic is long', 5), ['supercalifragilistic', 'is', 'long']);
};
