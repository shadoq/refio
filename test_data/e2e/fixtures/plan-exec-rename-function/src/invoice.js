'use strict';
const { calcTotal } = require('./total');

function invoiceLine(id, lines) {
    return `${id} ${calcTotal(lines)}`;
}

module.exports = { invoiceLine };
