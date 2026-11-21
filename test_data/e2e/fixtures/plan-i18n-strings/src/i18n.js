'use strict';
const en = require('../locales/en.json');

function t(key) {
    return en[key] ?? key;
}

module.exports = { t };
