'use strict';
const assert = require('node:assert/strict');
const { formatDate } = require('../src/formatDate');

module.exports = () => {
    assert.equal(formatDate(new Date(Date.UTC(2026, 0, 5))), '2026-01-05');
};
