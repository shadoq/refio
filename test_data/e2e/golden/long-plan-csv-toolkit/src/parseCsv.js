'use strict';
const { parseLine } = require('./parseLine');

function parseCsv(text) {
    return text.split(/\r?\n/).filter((l) => l !== '').map(parseLine);
}

module.exports = { parseCsv };
