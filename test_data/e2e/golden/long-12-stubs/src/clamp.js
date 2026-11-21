'use strict';

function clamp(x, lo, hi) {
    const a = Math.min(lo, hi);
    const b = Math.max(lo, hi);
    return Math.min(Math.max(x, a), b);
}

module.exports = { clamp };
