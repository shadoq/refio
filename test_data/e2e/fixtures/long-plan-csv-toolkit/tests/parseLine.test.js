'use strict';
const assert = require('node:assert/strict');
const { parseLine } = require('../src/parseLine');

module.exports = () => {
    assert.deepEqual(parseLine('a,b,c'), ['a', 'b', 'c']);
    assert.deepEqual(parseLine('"x,y",z'), ['x,y', 'z']);
    assert.deepEqual(parseLine('"say ""hi""",2'), ['say "hi"', '2']);
    assert.deepEqual(parseLine(''), ['']);
};
