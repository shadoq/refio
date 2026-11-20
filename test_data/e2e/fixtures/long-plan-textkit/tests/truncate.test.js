'use strict';
const assert = require('node:assert/strict');
const { truncate } = require('../src/truncate');

module.exports = () => {
    assert.equal(truncate('hello world', 8), 'hello w…');
    assert.equal(truncate('short', 10), 'short');
    assert.equal(truncate('abc', 1), '…');
};
