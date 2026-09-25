const test = require("node:test");
const assert = require("node:assert/strict");
const { spawnSync } = require("node:child_process");
const path = require("node:path");
const { regionTotals } = require("../src/regionTotals");
const { overallTotal } = require("../src/overallTotal");
const { buildReport } = require("../src/report");

const root = path.join(__dirname, "..");
const order = (region, status, amount_cents) => ({ region, status, amount_cents });

function runCli(dataFile) {
  return spawnSync(process.execPath, [path.join(root, "bin", "report.js"), path.join(root, "data", dataFile)], {
    encoding: "utf8",
  });
}

const EXAMPLE = [order("PL", "paid", 1000), order("PL", "canceled", 500), order("DE", "paid", 300), order("DE", "refunded", 100)];
const EXAMPLE_REPORT = "region;total_cents\nDE;300\nPL;1000\nTOTAL;1300\n";

test("regional totals ignore canceled orders", () => {
  assert.deepEqual(regionTotals(EXAMPLE), { DE: 300, PL: 1000 });
});

test("the overall total ignores canceled orders", () => {
  assert.equal(overallTotal(EXAMPLE), 1300);
});

test("paid orders contribute their amount and refunded orders contribute zero", () => {
  const orders = [order("PL", "paid", 250), order("PL", "refunded", 700), order("DE", "paid", 40)];
  assert.deepEqual(regionTotals(orders), { DE: 40, PL: 250 });
  assert.equal(overallTotal(orders), 290);
});

test("a region whose total is zero is still listed", () => {
  const orders = [order("SE", "refunded", 900), order("DE", "paid", 10)];
  assert.equal(buildReport(orders), "region;total_cents\nDE;10\nSE;0\nTOTAL;10\n");
});

test("an empty order list gives the header and a zero total", () => {
  assert.equal(buildReport([]), "region;total_cents\nTOTAL;0\n");
});

test("the report keeps the semicolon CSV contract for the documented example", () => {
  assert.equal(buildReport(EXAMPLE), EXAMPLE_REPORT);
});

test("the CLI prints the documented example exactly, ending with LF", () => {
  const r = runCli("sample.csv");
  assert.equal(r.status, 0, r.stderr);
  assert.equal(r.stdout, EXAMPLE_REPORT);
});

test("the CLI prints only the header and a zero total for an empty export", () => {
  const r = runCli("empty.csv");
  assert.equal(r.status, 0, r.stderr);
  assert.equal(r.stdout, "region;total_cents\nTOTAL;0\n");
});

test("the CLI report for the full order export", () => {
  // Worked out independently of this code base (awk over data/orders.csv): only paid
  // orders count; SE has refunded orders only.
  const r = runCli("orders.csv");
  assert.equal(r.status, 0, r.stderr);
  assert.equal(
    r.stdout,
    [
      "region;total_cents",
      "CZ;561664",
      "DE;402457",
      "ES;441440",
      "FR;538309",
      "IT;720783",
      "NL;811587",
      "PL;376734",
      "SE;0",
      "TOTAL;3852974",
      "",
    ].join("\n"),
  );
});
