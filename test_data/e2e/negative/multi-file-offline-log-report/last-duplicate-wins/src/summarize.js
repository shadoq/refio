"use strict";

// Builds the request-log report described in README.md.
const fs = require("node:fs");
const path = require("node:path");

function isNonEmptyString(v) {
  return typeof v === "string" && v.length > 0;
}

function isValidRecord(r) {
  return (
    r !== null &&
    typeof r === "object" &&
    !Array.isArray(r) &&
    isNonEmptyString(r.id) &&
    isNonEmptyString(r.service) &&
    Number.isInteger(r.status) &&
    r.status >= 100 &&
    r.status <= 599 &&
    Number.isInteger(r.latency_ms) &&
    r.latency_ms >= 0
  );
}

function p95(latencies) {
  const sorted = [...latencies].sort((a, b) => a - b);
  return sorted[Math.ceil(0.95 * sorted.length) - 1];
}

// Returns the report object for the given JSONL text.
function summarize(text) {
  let rejected = 0;
  let duplicates = 0;
  const seen = new Map();
  const byService = new Map();

  for (const line of text.split("\n")) {
    if (line.trim() === "") continue;
    let record;
    try {
      record = JSON.parse(line);
    } catch {
      rejected++;
      continue;
    }
    if (!isValidRecord(record)) {
      rejected++;
      continue;
    }
    if (seen.has(record.id)) {
      duplicates++;
      const prev = seen.get(record.id);
      const list = byService.get(prev.service);
      list.splice(list.indexOf(prev), 1);
      if (list.length === 0) byService.delete(prev.service);
    }
    seen.set(record.id, record);
    if (!byService.has(record.service)) byService.set(record.service, []);
    byService.get(record.service).push(record);
  }

  const services = [...byService.keys()]
    .sort((a, b) => (a < b ? -1 : a > b ? 1 : 0))
    .map((service) => {
      const records = byService.get(service);
      return {
        service,
        count: records.length,
        errors: records.filter((r) => r.status >= 500).length,
        p95_ms: p95(records.map((r) => r.latency_ms)),
      };
    });

  return { services, rejected, duplicates };
}

// Reads inputPath, writes the report to outputPath and returns the process exit code.
function writeReport(inputPath, outputPath) {
  let text;
  try {
    text = fs.readFileSync(inputPath, "utf8");
  } catch (err) {
    console.error(`cannot read input ${inputPath}: ${err.message}`);
    return 1;
  }
  const report = summarize(text);
  fs.mkdirSync(path.dirname(outputPath), { recursive: true });
  fs.writeFileSync(outputPath, JSON.stringify(report, null, 2) + "\n");
  return 0;
}

module.exports = { summarize, writeReport };
