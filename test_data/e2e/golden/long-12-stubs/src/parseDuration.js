'use strict';

function parseDuration(text) {
    const m = /^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$/.exec(text);
    if (!text || !m) {
        throw new Error('invalid duration: ' + text);
    }
    return Number(m[1] || 0) * 3600 + Number(m[2] || 0) * 60 + Number(m[3] || 0);
}

module.exports = { parseDuration };
