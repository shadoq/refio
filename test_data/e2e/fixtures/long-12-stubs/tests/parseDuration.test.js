'use strict';
const assert = require('node:assert/strict');
const { parseDuration } = require('../src/parseDuration');

module.exports = () => {
    assert.equal(parseDuration('1h30m15s'), 5415);
    assert.equal(parseDuration('45s'), 45);
    assert.equal(parseDuration('2m'), 120);
    assert.equal(parseDuration('1h'), 3600);
    assert.throws(() => parseDuration(''), Error);
    assert.throws(() => parseDuration('10x'), Error);
    assert.throws(() => parseDuration('m5'), Error);
};
