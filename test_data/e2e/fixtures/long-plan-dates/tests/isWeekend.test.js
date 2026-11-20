'use strict';
const assert = require('node:assert/strict');
const { isWeekend } = require('../src/isWeekend');

module.exports = () => {
    assert.equal(isWeekend('2026-11-21'), true);
    assert.equal(isWeekend('2026-11-22'), true);
    assert.equal(isWeekend('2026-11-23'), false);
};
