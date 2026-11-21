'use strict';

function chunk(arr, size) {
    if (!Number.isInteger(size) || size <= 0) {
        throw new RangeError('size must be a positive integer');
    }
    const out = [];
    for (let i = 0; i < arr.length; i += size) {
        out.push(arr.slice(i, i + size));
    }
    return out;
}

module.exports = { chunk };
