'use strict';
const assert = require('node:assert/strict');
const { formatCsv } = require('../src/formatCsv');
const { parseCsv } = require('../src/parseCsv');

module.exports = () => {
    assert.equal(formatCsv([['a', 'b'], ['1', '2']]), 'a,b\n1,2\n');
    assert.equal(formatCsv([['x,y', 'say "hi"']]), '"x,y","say ""hi"""\n');
    const rows = [['q"', 'a,b', 'plain']];
    assert.deepEqual(parseCsv(formatCsv(rows)), rows);
};
