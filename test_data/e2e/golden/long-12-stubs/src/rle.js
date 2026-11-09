'use strict';

function rle(s) {
    return s.replace(/(.)\1*/g, (run, ch) => ch + run.length);
}

module.exports = { rle };
