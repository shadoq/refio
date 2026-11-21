'use strict';
const { getJSON } = require('../lib/http-legacy');

function stocks(symbol, cb) {
    getJSON(`/stocks/${symbol}`, cb);
}

module.exports = { stocks };
