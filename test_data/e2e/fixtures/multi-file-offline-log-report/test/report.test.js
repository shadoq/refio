const test = require("node:test");
const assert = require("node:assert/strict");
const { spawnSync } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const root = path.join(__dirname, "..");
const cli = path.join(root, "bin", "report.js");
const data = (name) => path.join(root, "data", name);

const created = [];
test.after(() => {
  for (const dir of created) fs.rmSync(dir, { recursive: true, force: true });
});

function tempDir() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "log report "));
  created.push(dir);
  return dir;
}

function runReport(input, output) {
  return spawnSync(process.execPath, [cli, "--input", input, "--output", output], { encoding: "utf8" });
}

function reportFor(input) {
  const output = path.join(tempDir(), "summary.json");
  const r = runReport(input, output);
  assert.equal(r.status, 0, `report exited with ${r.status}: ${r.stderr}`);
  return JSON.parse(fs.readFileSync(output, "utf8"));
}

// Expected values below were worked out by hand from the files in data/.

test("two services, duplicates and rejected lines are summarised", () => {
  // Kept: checkout r1(200,120) r3(500,300) r6(404,80); auth r2(503,40) r5(201,15).
  // Rejected: truncated JSON, string status, empty id, negative latency, status 99,
  // fractional latency, a null line. Duplicates: the later r1 and the later r2.
  assert.deepEqual(reportFor(data("requests.jsonl")), {
    services: [
      { service: "auth", count: 2, errors: 1, p95_ms: 40 },
      { service: "checkout", count: 3, errors: 1, p95_ms: 300 },
    ],
    rejected: 7,
    duplicates: 2,
  });
});

test("the first valid record of a duplicated id is the one kept", () => {
  const report = reportFor(data("requests.jsonl"));
  const checkout = report.services.find((s) => s.service === "checkout");
  // The later r1 has latency 999; keeping it instead would make p95 999.
  assert.equal(checkout.p95_ms, 300);
});

test("an invalid record does not claim the id of a later valid record", () => {
  assert.deepEqual(reportFor(data("invalid-then-valid.jsonl")), {
    services: [{ service: "search", count: 1, errors: 0, p95_ms: 70 }],
    rejected: 1,
    duplicates: 0,
  });
});

test("p95 of 20 latencies uses nearest rank ceil(0.95 * n)", () => {
  // Latencies 1..20 in shuffled order; rank ceil(19) = 19 -> 19 ms. Two rows have status 500.
  assert.deepEqual(reportFor(data("latency-20.jsonl")), {
    services: [{ service: "search", count: 20, errors: 2, p95_ms: 19 }],
    rejected: 0,
    duplicates: 0,
  });
});

test("an empty input produces an empty report", () => {
  assert.deepEqual(reportFor(data("empty.jsonl")), { services: [], rejected: 0, duplicates: 0 });
});

test("missing parent directories of the output are created", () => {
  const output = path.join(tempDir(), "nested", "deeper", "summary.json");
  const r = runReport(data("latency-20.jsonl"), output);
  assert.equal(r.status, 0, r.stderr);
  assert.equal(JSON.parse(fs.readFileSync(output, "utf8")).services[0].count, 20);
});

test("input and output paths may contain spaces", () => {
  const dir = tempDir();
  const input = path.join(dir, "input files", "request log.jsonl");
  fs.mkdirSync(path.dirname(input), { recursive: true });
  fs.copyFileSync(data("invalid-then-valid.jsonl"), input);
  const output = path.join(dir, "report out", "summary file.json");
  const r = runReport(input, output);
  assert.equal(r.status, 0, r.stderr);
  assert.equal(JSON.parse(fs.readFileSync(output, "utf8")).services[0].p95_ms, 70);
});

test("a missing input fails and leaves an existing output untouched", () => {
  const dir = tempDir();
  const output = path.join(dir, "summary.json");
  fs.writeFileSync(output, "previous report\n");
  const r = runReport(path.join(dir, "does-not-exist.jsonl"), output);
  assert.notEqual(r.status, 0);
  assert.equal(fs.readFileSync(output, "utf8"), "previous report\n");
});

test("a missing input does not create the output", () => {
  const dir = tempDir();
  const output = path.join(dir, "new", "summary.json");
  const r = runReport(path.join(dir, "does-not-exist.jsonl"), output);
  assert.notEqual(r.status, 0);
  assert.equal(fs.existsSync(output), false);
});
