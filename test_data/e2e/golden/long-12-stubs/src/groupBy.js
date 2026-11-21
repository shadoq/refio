'use strict';

function groupBy(items, keyFn) {
    const out = {};
    for (const item of items) {
        const k = keyFn(item);
        (out[k] = out[k] || []).push(item);
    }
    return out;
}

module.exports = { groupBy };
