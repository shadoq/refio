'use strict';
const assert = require('node:assert/strict');
const { cartTotal } = require('../src/cart');

module.exports = () => {
    assert.equal(cartTotal([{ price: 50, qty: 2 }]), 100);
    assert.equal(cartTotal([{ price: 50, qty: 2 }], { coupon: 'SAVE10' }), 90);
};
