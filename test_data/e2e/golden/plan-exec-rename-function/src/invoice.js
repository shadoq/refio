'use strict';
const { computeTotal } = require('./total');

function invoiceLine(id, lines) {
    return `${id} ${computeTotal(lines)}`;
}

module.exports = { invoiceLine };
