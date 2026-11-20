'use strict';
const { computeTotal } = require('./total');

function cartSummary(lines) {
    return `Total: ${computeTotal(lines)}`;
}

module.exports = { cartSummary };
