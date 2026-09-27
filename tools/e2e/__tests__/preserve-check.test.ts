// Intent tests for the trusted "changed only what was allowed" check. Each case is a way a model
// can hand back a file that still contains the right fix but damaged the rest of it: a needle on the
// fix alone passes all of them, which is exactly why this check exists.
import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import {
  checkJsonExcept,
  checkRegion,
  checkScenario,
  ScenarioError,
} from "../lib/preserve-check.mjs";

const here = fileURLToPath(new URL(".", import.meta.url));
const e2eDir = join(here, "..", "..", "..", "test_data", "e2e");
const ledger = readFileSync(join(e2eDir, "fixtures", "large-file", "src", "Ledger.kt"), "utf8");
const pkg = readFileSync(join(e2eDir, "fixtures", "npm-config", "package.json"), "utf8");

// The scenario's own rule, so a change to large-file-edit.json is exercised here too.
const largeFileRule = JSON.parse(readFileSync(join(e2eDir, "large-file-edit.json"), "utf8")).assert
  .preserved_except[0];

const buggy = "fun totalBalance(credits: Int, debits: Int): Int = credits - debits";
const fixed = "fun totalBalance(credits: Int, debits: Int): Int = credits + debits";

test("fixing only the one function passes", () => {
  assert.equal(checkRegion(ledger, ledger.replace(buggy, fixed), largeFileRule.region), null);
});

test("a file cut short after the fix fails, even though the fix itself is there", () => {
  const fixedText = ledger.replace(buggy, fixed);
  const truncated = fixedText.slice(0, fixedText.indexOf(fixed) + fixed.length + 200);
  assert.match(checkRegion(ledger, truncated, largeFileRule.region) ?? "", /after the allowed region/);
});

test("touching a neighbouring helper fails", () => {
  const edited = ledger
    .replace(buggy, fixed)
    .replace("fun feeTier70(amount: Int): Int = amount * 70 / 10000", "fun feeTier70(amount: Int): Int = amount * 71 / 10000");
  assert.match(checkRegion(ledger, edited, largeFileRule.region) ?? "", /before the allowed region/);
});

test("a helper after the region that changed also fails", () => {
  const edited = ledger.replace(buggy, fixed).replace("amount / parts + 71", "amount / parts + 72");
  assert.match(checkRegion(ledger, edited, largeFileRule.region) ?? "", /after the allowed region/);
});

test("deleting the region's line instead of fixing it fails", () => {
  const edited = ledger.replace(buggy + "\r\n", "").replace(buggy + "\n", "");
  assert.notEqual(checkRegion(ledger, edited, largeFileRule.region), null);
});

test("only a line-ending change is not a content change", () => {
  const lf = ledger.replace(/\r\n/g, "\n").replace(buggy, fixed);
  const crlf = lf.replace(/\n/g, "\r\n");
  assert.equal(checkRegion(ledger, lf, largeFileRule.region), null);
  assert.equal(checkRegion(ledger, crlf, largeFileRule.region), null);
  assert.equal(checkRegion(ledger, lf.replace(/\n+$/, ""), largeFileRule.region), null);
});

test("a region that does not identify exactly one fixture line is a scenario bug, not a model failure", () => {
  assert.throws(() => checkRegion(ledger, ledger, { start: "fun feeTier" }), ScenarioError);
  assert.throws(() => checkRegion(ledger, ledger, { start: "no such line anywhere" }), ScenarioError);
});

test("a multi-line region is bounded by its end line", () => {
  const original = "a\nfun f() {\n  return 1\n}\nb\n";
  const region = { start: "^fun f", end: "^}" };
  assert.equal(checkRegion(original, "a\nfun f() {\n  const x = 2\n  return x\n}\nb\n", region), null);
  assert.notEqual(checkRegion(original, "a\nfun f() {\n  return 2\n}\nc\n", region), null);
});

test("adding scripts.build and nothing else passes", () => {
  const withBuild = pkg.replace('"test": "node test.js"', '"test": "node test.js",\n    "build": "node build.js"');
  assert.equal(checkJsonExcept(pkg, withBuild, ["scripts.build"]), null);
});

test("reformatting or reordering keys is not a structural change", () => {
  const obj = JSON.parse(pkg);
  const reordered = JSON.stringify({ scripts: { build: "node build.js", test: obj.scripts.test }, private: true, version: "1.2.3", name: "widget" });
  assert.equal(checkJsonExcept(pkg, reordered, ["scripts.build"]), null);
});

test("losing an existing field fails", () => {
  const obj = JSON.parse(pkg);
  delete obj.private;
  obj.scripts.build = "node build.js";
  assert.match(checkJsonExcept(pkg, JSON.stringify(obj), ["scripts.build"]) ?? "", /\$\.private \(removed\)/);
});

test("replacing the test script with the build fails", () => {
  const obj = JSON.parse(pkg);
  obj.scripts = { build: "node build.js" };
  assert.match(checkJsonExcept(pkg, JSON.stringify(obj), ["scripts.build"]) ?? "", /scripts\.test/);
});

test("a change to an allowed path's sibling fails", () => {
  const obj = JSON.parse(pkg);
  obj.version = "1.2.4";
  obj.scripts.build = "node build.js";
  assert.match(checkJsonExcept(pkg, JSON.stringify(obj), ["scripts.build"]) ?? "", /\$\.version/);
});

test("broken JSON fails instead of throwing", () => {
  assert.match(checkJsonExcept(pkg, pkg.replace(/}\s*$/, ""), ["scripts.build"]) ?? "", /not valid JSON/);
});

test("the command-line entry point reads files only and reports per rule", () => {
  const root = mkdtempSync(join(tmpdir(), "preserve-check-"));
  const fx = join(root, "fx");
  const proj = join(root, "proj");
  mkdirSync(fx);
  mkdirSync(proj);
  writeFileSync(join(fx, "package.json"), pkg);
  const scenarioPath = join(root, "scenario.json");
  writeFileSync(scenarioPath, JSON.stringify({ assert: { preserved_except: [{ path: "package.json", json_paths: ["scripts.build"] }] } }));
  const script = join(here, "..", "lib", "preserve-check.mjs");
  const run = () => spawnSync(process.execPath, [script, scenarioPath, fx, proj], { encoding: "utf8" });

  let r = run();
  assert.equal(r.status, 1);
  assert.match(r.stdout, /package\.json: missing after the run/);

  writeFileSync(join(proj, "package.json"), pkg);
  r = run();
  assert.equal(r.status, 0, r.stdout);

  writeFileSync(join(proj, "package.json"), "{}");
  r = run();
  assert.equal(r.status, 1);

  writeFileSync(scenarioPath, JSON.stringify({ assert: { preserved_except: [{ path: "package.json" }] } }));
  r = run();
  assert.equal(r.status, 2);
  assert.deepEqual(checkScenario({ assert: {} }, fx, proj), []);
});
