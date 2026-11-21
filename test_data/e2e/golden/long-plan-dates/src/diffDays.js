'use strict';
const { parseDate } = require('./parseDate');

function diffDays(a, b) {
    return Math.round((parseDate(a) - parseDate(b)) / 86400000);
}

module.exports = { diffDays };
