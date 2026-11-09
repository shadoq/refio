'use strict';
// Passes only when TIMEOUT_MS is twice the p95 latency the load test measures and the other
// settings are unchanged.
const { samples, p95 } = require('./tools/measure.js');
const config = require('./src/config.js');

const expected = 2 * p95(samples());
if (config.TIMEOUT_MS === expected && config.SERVICE_NAME === 'orders' && config.RETRIES === 2) {
    console.log('OK');
} else {
    console.log(`FAIL TIMEOUT_MS=${config.TIMEOUT_MS} SERVICE_NAME=${config.SERVICE_NAME} RETRIES=${config.RETRIES}`);
    process.exitCode = 1;
}
