'use strict';
const assert = require('node:assert/strict');
const { slugify } = require('../src/slugify');

module.exports = () => {
    assert.equal(slugify('Hello World'), 'hello-world');
    assert.equal(slugify('  Many   spaces  '), 'many-spaces');
    assert.equal(slugify('Rock & Roll!'), 'rock-roll');
    assert.equal(slugify('--already--dashed--'), 'already-dashed');
    assert.equal(slugify('Version 2.0'), 'version-2-0');
};
