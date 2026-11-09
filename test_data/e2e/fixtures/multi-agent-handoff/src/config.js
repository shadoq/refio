'use strict';
// Service settings. TIMEOUT_MS is still a placeholder: it must be twice the p95 latency that the
// load test (node tools/measure.js) reports.
module.exports = {
    SERVICE_NAME: 'orders',
    RETRIES: 2,
    TIMEOUT_MS: 0,
};
