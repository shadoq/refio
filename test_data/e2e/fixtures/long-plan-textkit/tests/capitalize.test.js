'use strict';
const assert = require('node:assert/strict');
const { capitalize } = require('../src/capitalize');

module.exports = () => {
    assert.equal(capitalize('hELLO'), 'Hello');
    assert.equal(capitalize(''), '');
};
