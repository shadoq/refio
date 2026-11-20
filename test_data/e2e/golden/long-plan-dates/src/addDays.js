'use strict';
const { parseDate } = require('./parseDate');
const { formatDate } = require('./formatDate');

function addDays(s, n) {
    return formatDate(new Date(parseDate(s).getTime() + n * 86400000));
}

module.exports = { addDays };
