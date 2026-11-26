'use strict';

const http = require('node:http');
const https = require('node:https');
const net = require('node:net');
const { lookup } = require('node:dns/promises');

const DEFAULT_TIMEOUT_MS = 5000;
const MAX_SOURCE_BYTES = 8 * 1024 * 1024;

/** Hosts the CDN layer serves; a download from them is pinned to one public address. */
const ORIGINS = new Set(['images.acme.example', 'cdn.acme.example']);

const NOT_PUBLIC = [
  /^10\./, /^127\./, /^169\.254\./, /^192\.168\./, /^172\.(1[6-9]|2[0-9]|3[01])\./,
  /^::$/, /^::1$/, /^::ffff:(10\.|127\.|169\.254\.|192\.168\.)/, /^f[cd][0-9a-f]{2}:/, /^fe[89ab][0-9a-f]:/,
];

function isPublicAddress(address) {
  return net.isIP(address) !== 0 && !NOT_PUBLIC.some((pattern) => pattern.test(address));
}

/** Collects the response up to `maxBytes`; the socket is closed as soon as the limit is crossed. */
function readResponse(response, maxBytes) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let received = 0;
    response.on('data', (chunk) => {
      received += chunk.length;
      if (received > maxBytes) {
        response.destroy();
        reject(new RangeError('source is larger than the limit'));
        return;
      }
      chunks.push(chunk);
    });
    response.on('end', () => resolve(Buffer.concat(chunks)));
    response.on('error', reject);
  });
}

function download(client, url, options, pinnedAddress) {
  return new Promise((resolve, reject) => {
    const request = client.get(url, {
      timeout: options.timeoutMs ?? DEFAULT_TIMEOUT_MS,
      lookup: pinnedAddress === null
        ? undefined
        : (hostname, family, callback) => callback(null, pinnedAddress, net.isIP(pinnedAddress)),
    }, (response) => {
      if (response.statusCode !== 200) {
        response.resume();
        reject(new Error(`source answered ${response.statusCode}`));
        return;
      }
      readResponse(response, options.maxBytes ?? MAX_SOURCE_BYTES).then(resolve, reject);
    });
    request.on('timeout', () => request.destroy(new Error('source timed out')));
    request.on('error', reject);
  });
}

/**
 * Downloads a file from the CDN layer: https only, a listed origin, and the connection is
 * pinned to an address the name resolved to that is not private.
 */
async function fetchFromOrigin(address, options = {}) {
  const url = new URL(address);
  if (url.protocol !== 'https:' || !ORIGINS.has(url.hostname)) {
    throw new Error(`origin is not allowed: ${url.hostname}`);
  }
  const records = await lookup(url.hostname, { all: true });
  const record = records.find((entry) => isPublicAddress(entry.address));
  if (!record) throw new Error(`origin resolves to a private address: ${url.hostname}`);
  return download(https, url, options, record.address);
}

/**
 * Partner feeds pass the full address of the image in `src`. The address is used exactly as
 * given, so that partners can serve from their own host names, including hosts that only the
 * cluster can reach.
 */
async function fetchByUrl(address, options = {}) {
  const url = new URL(address);
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new Error(`unsupported source protocol: ${url.protocol}`);
  }
  return download(url.protocol === 'https:' ? https : http, url, options, null);
}

module.exports = { fetchByUrl, fetchFromOrigin, isPublicAddress };
