'use strict';
const assert = require('node:assert/strict');
const { columnStats } = require('../src/columnStats');

module.exports = () => {
    assert.deepEqual(columnStats('n,v\na,1\nb,3\nc,x\n', 'v'), { count: 2, min: 1, max: 3, mean: 2 });
    assert.deepEqual(columnStats('n,v\n', 'v'), { count: 0, min: null, max: null, mean: null });
};
