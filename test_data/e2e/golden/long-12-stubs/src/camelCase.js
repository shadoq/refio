'use strict';

function camelCase(s) {
    const words = s.trim().split(/[\s_-]+/).filter(Boolean).map((w) => w.toLowerCase());
    return words.map((w, i) => (i === 0 ? w : w[0].toUpperCase() + w.slice(1))).join('');
}

module.exports = { camelCase };
