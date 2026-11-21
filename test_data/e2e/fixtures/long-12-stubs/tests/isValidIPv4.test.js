'use strict';
const assert = require('node:assert/strict');
const { isValidIPv4 } = require('../src/isValidIPv4');

module.exports = () => {
    assert.equal(isValidIPv4('192.168.0.1'), true);
    assert.equal(isValidIPv4('0.0.0.0'), true);
    assert.equal(isValidIPv4('255.255.255.255'), true);
    assert.equal(isValidIPv4('256.1.1.1'), false);
    assert.equal(isValidIPv4('1.2.3'), false);
    assert.equal(isValidIPv4('01.2.3.4'), false); // leading zeros are not allowed
    assert.equal(isValidIPv4('1.2.3.4 '), false);
    assert.equal(isValidIPv4('a.b.c.d'), false);
};
