'use strict';

function median(nums) {
    if (nums.length === 0) {
        return NaN;
    }
    const s = [...nums].sort((a, b) => a - b);
    const m = Math.floor(s.length / 2);
    return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
}

module.exports = { median };
