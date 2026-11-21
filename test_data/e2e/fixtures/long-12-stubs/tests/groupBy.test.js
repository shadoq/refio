'use strict';
const assert = require('node:assert/strict');
const { groupBy } = require('../src/groupBy');

module.exports = () => {
    const words = ['apple', 'avocado', 'banana', 'blueberry', 'cherry'];
    assert.deepEqual(groupBy(words, (w) => w[0]), {
        a: ['apple', 'avocado'],
        b: ['banana', 'blueberry'],
        c: ['cherry'],
    });
    assert.deepEqual(groupBy([1, 2, 3, 4], (n) => (n % 2 === 0 ? 'even' : 'odd')), { odd: [1, 3], even: [2, 4] });
    assert.deepEqual(groupBy([], (x) => x), {});
};
