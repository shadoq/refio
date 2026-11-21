'use strict';

function flatten(arr, depth = Infinity) {
    return arr.flat(depth);
}

module.exports = { flatten };
