#!/usr/bin/env node
/*
 * Acceptance gate for the downloader rewrite.
 *
 * Drives the public downloadPipeline API end to end: resolved values, the
 * SAVED side effect and the exact rejection message of every failure path.
 * The adapter below settles exactly once whether the module reports back
 * through the legacy error-first convention or by returning a Promise, so the
 * gate passes both on the shipped implementation and on a behaviour-preserving
 * async/await rewrite.
 *
 * This file is part of the scenario definition - editing it is not a fix.
 *
 * Run: node checks.js
 */
"use strict";

const path = require("path");
const downloader = require(path.join(__dirname, "src", "downloader.js"));

let failures = 0;

function check(name, ok) {
  console.log((ok ? "PASS" : "FAIL") + " - " + name);
  if (!ok) {
    failures += 1;
  }
}

// Invoke the pipeline through an adapter that accepts either calling
// convention and settles exactly once.
function run(url, opts) {
  return new Promise((resolve, reject) => {
    let settled = false;
    const settle = (err, value) => {
      if (settled) {
        return;
      }
      settled = true;
      if (err) {
        reject(err);
      } else {
        resolve(value);
      }
    };
    const returned = downloader.downloadPipeline(url, opts, (err, value) => settle(err, value));
    if (returned && typeof returned.then === "function") {
      returned.then((value) => settle(null, value), (err) => settle(err));
    }
  });
}

async function main() {
  // 1. happy path: resolved value plus the SAVED side effect
  let r = await run("dl://u-1/report-q3", {});
  check(
    "happy path resolves with the saved record",
    !!r &&
      r.savedAs === "report-q3.bin" &&
      r.bytes === 2048 &&
      !!downloader.SAVED["report-q3.bin"] &&
      downloader.SAVED["report-q3.bin"].bytes === 2048
  );

  // 2. options are honoured end to end
  r = await run("dl://u-3/logo-blob", { compact: true, prefix: "cache" });
  check(
    "opts.compact halves the payload and opts.prefix names the file",
    !!r && r.savedAs === "cache-logo-blob.bin" && r.bytes === 256
  );
  check(
    "compact records are flagged in SAVED",
    !!downloader.SAVED["cache-logo-blob.bin"] &&
      downloader.SAVED["cache-logo-blob.bin"].compact === true
  );

  // 3. every failure path rejects with its exact legacy message
  const cases = [
    ["dl://u-1/nope", {}, "asset not found: nope"],
    ["dl://u-99/report-q3", {}, "unknown user: u-99"],
    ["dl://u-2/report-q3", {}, "permission denied: u-2 lacks asset:read"],
    ["dl://u-1/video-teaser", {}, "transform failed: unsupported encoding brotli"],
    ["dl://u-3/dataset-bulk", {}, "save failed: quota exceeded (900000 > 262144)"],
    ["https://cdn.example.com/report-q3", {}, "malformed url: https://cdn.example.com/report-q3"]
  ];
  for (const [url, opts, message] of cases) {
    let err = null;
    try {
      await run(url, opts);
    } catch (e) {
      err = e;
    }
    check("rejects with: " + message, !!err && err.message === message);
  }

  // 4. repeated runs keep resolving (no state stuck between calls)
  r = await run("dl://u-1/report-q3", {});
  check(
    "a second run of the same url still resolves",
    !!r && r.savedAs === "report-q3.bin" && r.bytes === 2048
  );

  console.log();
  if (failures > 0) {
    console.log("checks: " + failures + " check(s) FAILED");
    process.exit(1);
  }
  console.log("checks: all checks passed");
}

main().then(
  () => {},
  (err) => {
    console.error("gate crashed:", err);
    process.exit(1);
  }
);
