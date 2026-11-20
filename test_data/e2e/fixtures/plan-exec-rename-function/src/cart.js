'use strict';
const { calcTotal } = require('./total');

function cartSummary(lines) {
    return `Total: ${calcTotal(lines)}`;
}

module.exports = { cartSummary };
