'use strict';
const { getJSON } = require('../lib/http-legacy');

function news(topic, cb) {
    getJSON(`/news/${topic}`, cb);
}

module.exports = { news };
