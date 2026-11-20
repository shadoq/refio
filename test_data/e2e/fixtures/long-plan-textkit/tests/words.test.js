'use strict';
const assert = require('node:assert/strict');
const { words } = require('../src/words');

module.exports = () => {
    assert.deepEqual(words('  hello   big\tworld '), ['hello', 'big', 'world']);
    assert.deepEqual(words(''), []);
};
