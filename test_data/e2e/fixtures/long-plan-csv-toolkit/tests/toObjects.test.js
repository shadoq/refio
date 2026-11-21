'use strict';
const assert = require('node:assert/strict');
const { toObjects } = require('../src/toObjects');

module.exports = () => {
    assert.deepEqual(toObjects('name,age\nAda,36\nAlan,41\n'), [{ name: 'Ada', age: '36' }, { name: 'Alan', age: '41' }]);
    assert.deepEqual(toObjects('name\n'), []);
};
