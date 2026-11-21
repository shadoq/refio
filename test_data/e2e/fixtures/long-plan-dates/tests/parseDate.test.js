'use strict';
const assert = require('node:assert/strict');
const { parseDate } = require('../src/parseDate');

module.exports = () => {
    assert.equal(parseDate('2026-02-28').getTime(), Date.UTC(2026, 1, 28));
    assert.throws(() => parseDate('2026-02-30'));
    assert.throws(() => parseDate('28.02.2026'));
};
