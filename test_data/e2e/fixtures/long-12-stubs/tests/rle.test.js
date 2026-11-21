'use strict';
const assert = require('node:assert/strict');
const { rle } = require('../src/rle');

module.exports = () => {
    assert.equal(rle('aaabcc'), 'a3b1c2');
    assert.equal(rle('abc'), 'a1b1c1');
    assert.equal(rle(''), '');
    assert.equal(rle('zzzzzzzzzzzz'), 'z12');
};
