'use strict';

function wordFreq(text) {
    const out = {};
    for (const w of text.toLowerCase().match(/[a-z0-9']+/g) || []) {
        out[w] = (out[w] || 0) + 1;
    }
    return out;
}

module.exports = { wordFreq };
