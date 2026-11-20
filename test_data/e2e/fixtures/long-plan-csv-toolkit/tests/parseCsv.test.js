'use strict';
const assert = require('node:assert/strict');
const { parseCsv } = require('../src/parseCsv');

module.exports = () => {
    assert.deepEqual(parseCsv('a,b\n1,2\n'), [['a', 'b'], ['1', '2']]);
    assert.deepEqual(parseCsv('a,b\r\n1,2'), [['a', 'b'], ['1', '2']]);
    assert.deepEqual(parseCsv(''), []);
};
