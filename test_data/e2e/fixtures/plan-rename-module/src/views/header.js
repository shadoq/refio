'use strict';
const { titleCase } = require('../utils/strings');

module.exports = (title) => `<h1>${titleCase(title)}</h1>`;
