'use strict';
const { parseDate } = require('./parseDate');

function isWeekend(s) {
    const day = parseDate(s).getUTCDay();
    return day === 0 || day === 6;
}

module.exports = { isWeekend };
