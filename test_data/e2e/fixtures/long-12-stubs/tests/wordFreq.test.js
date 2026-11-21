'use strict';
const assert = require('node:assert/strict');
const { wordFreq } = require('../src/wordFreq');

module.exports = () => {
    assert.deepEqual(wordFreq('The cat and the hat.'), { the: 2, cat: 1, and: 1, hat: 1 });
    assert.deepEqual(wordFreq('Go, go, GO!'), { go: 3 });
    assert.deepEqual(wordFreq(''), {});
};
