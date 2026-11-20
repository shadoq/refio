'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const validators = require('../src/validators');
const { submit } = require('../src/form');

assert.equal(validators.isEmail('a@b.co'), true);
assert.equal(validators.isEmail('nope'), false);
assert.equal(validators.isPhone('+48 600 700 800'), true);
assert.equal(validators.isPhone('12'), false);
assert.deepEqual(submit({ email: 'a@b.co', phone: '+48 600 700 800' }), { ok: true });
assert.deepEqual(submit({ email: 'nope', phone: '12' }), { ok: false, errors: ['email', 'phone'] });
const form = fs.readFileSync(path.join(__dirname, '..', 'src', 'form.js'), 'utf8');
assert.ok(!/function\s+isEmail|function\s+isPhone/.test(form), 'src/form.js must use src/validators.js, not its own copies');
console.log('ok');
