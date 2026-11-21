'use strict';
const assert = require('node:assert/strict');
const { diffDays } = require('../src/diffDays');

module.exports = () => {
    assert.equal(diffDays('2026-03-01', '2026-02-27'), 2);
    assert.equal(diffDays('2026-01-01', '2026-01-01'), 0);
    assert.equal(diffDays('2025-12-31', '2026-01-01'), -1);
};
