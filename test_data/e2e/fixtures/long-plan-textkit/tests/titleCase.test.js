'use strict';
const assert = require('node:assert/strict');
const { titleCase } = require('../src/titleCase');

module.exports = () => {
    assert.equal(titleCase('the lord OF the rings'), 'The Lord of the Rings');
    assert.equal(titleCase('a tale'), 'A Tale');
};
