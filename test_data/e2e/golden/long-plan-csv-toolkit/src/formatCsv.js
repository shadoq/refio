'use strict';

function formatCsv(rows) {
    const cell = (v) => (/[",\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v);
    return rows.map((r) => r.map(cell).join(',') + '\n').join('');
}

module.exports = { formatCsv };
