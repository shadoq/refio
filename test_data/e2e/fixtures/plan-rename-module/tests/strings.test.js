'use strict';
const assert = require('node:assert/strict');
const { slugify, titleCase } = require('../src/utils/strings');

module.exports = () => {
    assert.equal(slugify(' Hello, World! '), 'hello-world');
    assert.equal(titleCase('hello world'), 'Hello World');
};
