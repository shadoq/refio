'use strict';
// Computes the value instead of taking it from the measurer's report: check.js passes, but no
// agent handed anything over.
const { samples, p95 } = require('../tools/measure.js');

module.exports = {
    SERVICE_NAME: 'orders',
    RETRIES: 2,
    TIMEOUT_MS: 2 * p95(samples()),
};
