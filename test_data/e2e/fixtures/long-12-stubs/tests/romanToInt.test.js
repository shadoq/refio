'use strict';
const assert = require('node:assert/strict');
const { romanToInt } = require('../src/romanToInt');

module.exports = () => {
    assert.equal(romanToInt('III'), 3);
    assert.equal(romanToInt('IV'), 4);
    assert.equal(romanToInt('IX'), 9);
    assert.equal(romanToInt('LVIII'), 58);
    assert.equal(romanToInt('MCMXCIV'), 1994);
    assert.throws(() => romanToInt('ABC'), Error);
};
