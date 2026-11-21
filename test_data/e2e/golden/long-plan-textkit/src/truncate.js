'use strict';

function truncate(s, max) {
    return s.length <= max ? s : s.slice(0, Math.max(0, max - 1)) + '…';
}

module.exports = { truncate };
