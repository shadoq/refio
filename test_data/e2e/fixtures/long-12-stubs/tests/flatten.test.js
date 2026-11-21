'use strict';
const assert = require('node:assert/strict');
const { flatten } = require('../src/flatten');

module.exports = () => {
    assert.deepEqual(flatten([1, [2, [3, [4]]]]), [1, 2, 3, 4]);
    assert.deepEqual(flatten([1, [2, [3, [4]]]], 1), [1, 2, [3, [4]]]);
    assert.deepEqual(flatten([1, [2, [3, [4]]]], 0), [1, [2, [3, [4]]]]);
    assert.deepEqual(flatten([]), []);
};
