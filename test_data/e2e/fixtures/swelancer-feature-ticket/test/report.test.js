"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const { spawnSync } = require("node:child_process");
const { render } = require("../src/report");

const ROOT = path.join(__dirname, "..");
const CLI = path.join(ROOT, "src", "cli.js");

function cli(...args) {
  const r = spawnSync(process.execPath, [CLI, ...args], { cwd: ROOT, encoding: "utf8" });
  return { code: r.status, out: r.stdout, err: r.stderr };
}

const SAMPLE = [
  { id: 12, customer: "Zed", status: "paid", total_cents: 1005, placed_on: "2024-05-02" },
  { id: 7, customer: "Acme, Ltd", status: "shipped", total_cents: 250000, placed_on: "2024-05-02" },
  { id: 100, customer: 'Say "hi" Co', status: "pending", total_cents: 99, placed_on: "2024-04-30" },
  { id: 9, customer: "Two\nLines", status: "refunded", total_cents: 0, placed_on: "2024-05-03" },
];

const DEFAULT_TEXT = [
  "#1003  Hollow Pine, Ltd       paid        $120.50",
  "#1001  Maple Row Goods        shipped      $49.99",
  '#1002  Quill "Q" Supplies     refunded     $25.00',
  "#1004  Nettle & Co            pending       $1.00",
  "#999  Orchard Lane           paid         $70.00",
  "#1005  Maple Row Goods        paid        $300.00",
  "#1006  Harbor Lights          shipped       $8.75",
  "7 orders, total $575.24",
  "",
].join("\n");

test("csv has the agreed header, dollar amounts and a trailing newline", () => {
  const out = render([SAMPLE[0]], { format: "csv" });
  assert.equal(out, "id,customer,status,total,placed_on\n12,Zed,paid,10.05,2024-05-02\n");
});

test("csv quotes fields with commas, quotes and line breaks (RFC 4180)", () => {
  const out = render(SAMPLE, { format: "csv" });
  assert.ok(out.includes('7,"Acme, Ltd",shipped,2500.00,2024-05-02\n'), out);
  assert.ok(out.includes('100,"Say ""hi"" Co",pending,0.99,2024-04-30\n'), out);
  assert.ok(out.includes('9,"Two\nLines",refunded,0.00,2024-05-03\n'), out);
});

test("csv rows are sorted by placed_on and then by numeric id", () => {
  const out = render(SAMPLE, { format: "csv" });
  const ids = out
    .trimEnd()
    .split(/\r?\n(?=\d)/)
    .slice(1)
    .map((row) => row.split(",")[0]);
  assert.deepEqual(ids, ["100", "7", "12", "9"]);
});

test("an empty selection still gives the csv header", () => {
  assert.equal(render(SAMPLE, { format: "csv", status: "paid", since: "2030-01-01" }), "id,customer,status,total,placed_on\n");
});

test("status filter takes a comma separated list and ignores case and spaces", () => {
  const out = render(SAMPLE, { format: "csv", status: "Paid, SHIPPED" });
  assert.deepEqual(
    out.trimEnd().split("\n").slice(1).map((l) => l.split(",")[0]).sort(),
    ["12", "7"]
  );
});

test("an unknown status is an error that quotes the value as typed", () => {
  assert.throws(() => render(SAMPLE, { format: "csv", status: "paid,Shiped" }), { message: "Unknown status: Shiped" });
});

test("since is inclusive and works for the text format too", () => {
  const out = render(SAMPLE, { since: "2024-05-02" });
  assert.ok(out.includes("#12 "), out);
  assert.ok(out.includes("#7 "), out);
  assert.ok(!out.includes("#100 "), out);
  assert.ok(out.includes("3 orders, total $2510.05"), out);
});

test("a malformed or impossible date is rejected", () => {
  assert.throws(() => render(SAMPLE, { since: "05/02/2024" }), { message: "Invalid date: 05/02/2024" });
  assert.throws(() => render(SAMPLE, { since: "2024-13-40" }), { message: "Invalid date: 2024-13-40" });
});

test("filters combine with AND", () => {
  const out = render(SAMPLE, { format: "csv", status: "paid,pending", since: "2024-05-01" });
  assert.equal(out, "id,customer,status,total,placed_on\n12,Zed,paid,10.05,2024-05-02\n");
});

test("an unknown format is rejected", () => {
  assert.throws(() => render(SAMPLE, { format: "xml" }), { message: "Unknown format: xml" });
});

test("cli: default report output is unchanged", () => {
  const r = cli("report");
  assert.equal(r.code, 0);
  assert.equal(r.out, DEFAULT_TEXT);
});

test("cli: csv with filters", () => {
  const r = cli("report", "--format", "csv", "--status", "paid,shipped", "--since", "2024-02-01");
  assert.equal(r.code, 0, r.err);
  assert.equal(
    r.out,
    [
      "id,customer,status,total,placed_on",
      "999,Orchard Lane,paid,70.00,2024-02-03",
      '1003,"Hollow Pine, Ltd",paid,120.50,2024-02-03',
      "1005,Maple Row Goods,paid,300.00,2024-03-01",
      "1006,Harbor Lights,shipped,8.75,2024-03-09",
      "",
    ].join("\n")
  );
  assert.equal(r.err, "");
});

test("cli: invalid input exits with 2, a message on stderr and nothing on stdout", () => {
  for (const [args, message] of [
    [["report", "--status", "bogus"], "Unknown status: bogus"],
    [["report", "--since", "yesterday"], "Invalid date: yesterday"],
    [["report", "--format", "pdf"], "Unknown format: pdf"],
  ]) {
    const r = cli(...args);
    assert.equal(r.code, 2, args.join(" "));
    assert.equal(r.out, "", args.join(" "));
    assert.ok(r.err.includes(message), r.err);
  }
});
