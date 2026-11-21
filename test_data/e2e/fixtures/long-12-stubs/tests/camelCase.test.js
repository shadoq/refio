'use strict';
const assert = require('node:assert/strict');
const { camelCase } = require('../src/camelCase');

module.exports = () => {
    assert.equal(camelCase('hello world'), 'helloWorld');
    assert.equal(camelCase('Hello-World_foo bar'), 'helloWorldFooBar');
    assert.equal(camelCase('  leading and trailing  '), 'leadingAndTrailing');
    assert.equal(camelCase('ALL CAPS'), 'allCaps');
    assert.equal(camelCase(''), '');
};
