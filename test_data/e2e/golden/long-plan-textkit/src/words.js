'use strict';

function words(s) {
    return s.split(/\s+/).filter(Boolean);
}

module.exports = { words };
