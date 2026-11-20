'use strict';

function getJSON(url) {
    return Promise.resolve({ url });
}

module.exports = { getJSON };
