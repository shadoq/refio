'use strict';
const { getJSON } = require('../lib/http-legacy');

function weather(city, cb) {
    getJSON(`/weather/${city}`, cb);
}

module.exports = { weather };
