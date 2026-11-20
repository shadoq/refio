'use strict';
const assert = require('node:assert/strict');
const { isPalindrome } = require('../src/isPalindrome');

module.exports = () => {
    assert.equal(isPalindrome('A man, a plan, a canal: Panama'), true);
    assert.equal(isPalindrome('Refio'), false);
    assert.equal(isPalindrome(''), true);
};
