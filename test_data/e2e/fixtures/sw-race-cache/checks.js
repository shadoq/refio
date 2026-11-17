#!/usr/bin/env node
'use strict';

/*
 * Acceptance gate for the request-cache coalescing fix.
 *
 * Replays the concurrent-render storm from the issue (two gets racing on a
 * cold key), the warmup batch, the rejection path and TTL expiry. Exit 0 only
 * when every check passes.
 *
 * This file is part of the scenario definition - editing it is not a fix.
 *
 * Run: node checks.js
 */

const { RequestCache } = require('./src/cache');

const failures = [];

function check(name, ok) {
  console.log((ok ? 'PASS' : 'FAIL') + ' - ' + name);
  if (!ok) {
    failures.push(name);
  }
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// --- concurrent renders of the same cold profile -------------------------------
async function concurrentGets() {
  let calls = 0;
  const cache = new RequestCache(async (key) => {
    calls += 1;
    await sleep(20); // hold the load open so a second get can arrive
    return { key, servedBy: calls };
  });
  const [a, b] = await Promise.all([cache.get('profile:7'), cache.get('profile:7')]);
  check('concurrent gets trigger exactly one underlying load', calls === 1);
  check('concurrent gets observe the same value object', a === b);
  await cache.get('profile:7');
  check('sequential get after the burst is served from the cache', calls === 1);
}

// --- the nightly warmup batch ---------------------------------------------------
async function warmBatch() {
  let calls = 0;
  const cache = new RequestCache(async (key) => {
    calls += 1;
    await sleep(10);
    return { key };
  });
  await cache.warm(['a', 'a', 'b']);
  check('warm coalesces duplicate keys into one load each', calls === 2);
}

// --- a failing upstream must reject every caller and allow a retry --------------
async function rejectionCleanup() {
  let attempts = 0;
  const boom = new Error('upstream unavailable');
  const cache = new RequestCache(async () => {
    attempts += 1;
    if (attempts === 1) {
      throw boom;
    }
    return { ok: true };
  });
  const results = await Promise.allSettled([cache.get('k'), cache.get('k')]);
  check(
    'a failed load rejects every joined caller',
    results.every((r) => r.status === 'rejected' && r.reason === boom)
  );
  const value = await cache.get('k');
  check('a failed load is not cached and can be retried', value.ok === true);
}

// --- entries still expire -------------------------------------------------------
async function ttlExpiry() {
  let calls = 0;
  const cache = new RequestCache(
    async (key) => {
      calls += 1;
      return { key };
    },
    { ttlMs: 15 }
  );
  await cache.get('k');
  await sleep(40);
  await cache.get('k');
  check('expired entries are reloaded', calls === 2);
}

async function main() {
  await concurrentGets();
  await warmBatch();
  await rejectionCleanup();
  await ttlExpiry();

  console.log();
  if (failures.length > 0) {
    console.log(`checks: ${failures.length} check(s) FAILED: ${failures.join('; ')}`);
    process.exit(1);
  }
  console.log('checks: all checks passed');
}

main();
