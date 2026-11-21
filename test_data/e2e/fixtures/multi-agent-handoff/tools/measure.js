'use strict';
// Load test stand-in: 1000 request latencies from a fixed seed, so every run measures the same p95.
function samples(count = 1000, seed = 20261109) {
    let state = seed;
    const out = [];
    for (let i = 0; i < count; i++) {
        state = (state * 48271) % 2147483647;
        out.push(40 + (state % 460));
    }
    return out;
}

function p95(values) {
    const sorted = [...values].sort((a, b) => a - b);
    return sorted[Math.ceil(0.95 * sorted.length) - 1];
}

if (require.main === module) {
    console.log(`p95 latency: ${p95(samples())} ms`);
}

module.exports = { samples, p95 };
