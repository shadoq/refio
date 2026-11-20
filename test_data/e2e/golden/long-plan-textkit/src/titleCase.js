'use strict';
const { capitalize } = require('./capitalize');

function titleCase(s) {
    const small = new Set(['a', 'an', 'the', 'of', 'and', 'in', 'on']);
    return s.split(' ').map((w, i) => (i > 0 && small.has(w.toLowerCase()) ? w.toLowerCase() : capitalize(w))).join(' ');
}

module.exports = { titleCase };
