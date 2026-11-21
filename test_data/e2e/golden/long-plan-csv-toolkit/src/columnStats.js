'use strict';
const { toObjects } = require('./toObjects');

function columnStats(text, column) {
    const nums = toObjects(text).map((r) => Number(r[column])).filter(Number.isFinite);
    if (nums.length === 0) return { count: 0, min: null, max: null, mean: null };
    const sum = nums.reduce((a, b) => a + b, 0);
    return { count: nums.length, min: Math.min(...nums), max: Math.max(...nums), mean: sum / nums.length };
}

module.exports = { columnStats };
