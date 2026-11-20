'use strict';
const { parseCsv } = require('./parseCsv');

function toObjects(text) {
    const [header, ...rows] = parseCsv(text);
    if (!header) return [];
    return rows.map((r) => Object.fromEntries(header.map((h, i) => [h, r[i] ?? ''])));
}

module.exports = { toObjects };
