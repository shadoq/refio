'use strict';

const { version } = require('../package.json');

function positiveInteger(raw, fallback) {
  const value = Number.parseInt(raw ?? '', 10);
  return Number.isInteger(value) && value > 0 ? value : fallback;
}

module.exports = {
  version,
  bindAddress: process.env.BIND_ADDRESS ?? '127.0.0.1',
  port: positiveInteger(process.env.PORT, 8080),
  maxBodyBytes: positiveInteger(process.env.MAX_BODY_BYTES, 16 * 1024),
  cacheDir: process.env.CACHE_DIR ?? '/var/cache/resizer',
};
