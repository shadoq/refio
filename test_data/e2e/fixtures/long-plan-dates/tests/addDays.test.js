'use strict';
const assert = require('node:assert/strict');
const { addDays } = require('../src/addDays');

module.exports = () => {
    assert.equal(addDays('2026-02-27', 2), '2026-03-01');
    assert.equal(addDays('2028-02-28', 1), '2028-02-29');
    assert.equal(addDays('2026-01-01', -1), '2025-12-31');
};
