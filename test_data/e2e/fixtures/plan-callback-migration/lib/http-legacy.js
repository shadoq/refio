'use strict';

function getJSON(url, callback) {
    setImmediate(() => callback(null, { url }));
}

module.exports = { getJSON };
