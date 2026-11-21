'use strict';
const assert = require('node:assert/strict');
const total = require('../src/total');
const { cartSummary } = require('../src/cart');
const { invoiceLine } = require('../src/invoice');

assert.equal(typeof total.computeTotal, 'function', 'src/total.js must export computeTotal');
assert.equal(total.calcTotal, undefined, 'the old name calcTotal must be gone');
assert.equal(total.computeTotal([{ price: 2, qty: 3 }]), 6);
assert.equal(cartSummary([{ price: 2, qty: 3 }]), 'Total: 6');
assert.equal(invoiceLine('A1', [{ price: 5, qty: 1 }]), 'A1 5');
console.log('ok');
