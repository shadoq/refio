// Trusted post-run check: "the agent changed only what it was allowed to change".
//
// Runs from the harness directory, never from the project under test, and only READS files: the
// agent can edit anything in its project, but it cannot edit this script or the pristine fixture it
// compares against. Plain Node ESM with no dependencies, so e2e-run.sh, e2e-run.ps1 and
// validate-scenarios.sh can all call the same implementation.
//
// Scenario field (assert.preserved_except), an array of rules, one of two shapes per entry:
//
//   { "path": "src/Ledger.kt", "region": { "start": "<js regex>", "end": "<js regex>"? } }
//       Every line of the original file outside the region must still be there, unchanged, before
//       and after whatever now stands in the region's place. The region is found in the ORIGINAL
//       fixture: the one line matching `start` (it must match exactly one line), through the first
//       following line matching `end` (inclusive; without `end` the region is that single line).
//
//   { "path": "package.json", "json_paths": ["scripts.build"] }
//       Both files parse as JSON and are structurally equal once the listed dotted paths are
//       removed from both. Object key order is ignored; array order is not.
//
// Line endings: CRLF and LF compare equal, and trailing newlines at the end of the file are
// ignored. A checkout on Windows turns the fixture into CRLF while an agent's write tool may emit
// LF; that difference is the environment's, not a change the agent made to the content.
//
// CLI: node preserve-check.mjs <scenario.json> <fixtureDir> <projectDir>
//   exit 0: every rule holds (or the scenario has none)
//   exit 1: a rule failed; one reason per line on stdout
//   exit 2: the scenario itself is wrong (bad rule, region not found in the fixture); reason on stdout
import { existsSync, readFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export class ScenarioError extends Error {}

/** CRLF/CR -> LF, drop a UTF-8 BOM and every trailing newline. */
export function normalizeText(text) {
  let t = text.startsWith("﻿") ? text.slice(1) : text;
  t = t.replace(/\r\n?/g, "\n");
  return t.replace(/\n+$/, "");
}

/**
 * Checks that `actual` keeps every line of `original` outside the region. Returns null when it
 * holds, otherwise a short reason. Throws ScenarioError when the region is not in `original`.
 */
export function checkRegion(original, actual, region, label = "file") {
  if (!region || typeof region.start !== "string" || region.start === "") {
    throw new ScenarioError(`${label}: region needs a non-empty "start" regex`);
  }
  const lines = normalizeText(original).split("\n");
  const startRx = new RegExp(region.start);
  const starts = [];
  lines.forEach((l, i) => {
    if (startRx.test(l)) starts.push(i);
  });
  if (starts.length !== 1) {
    throw new ScenarioError(
      `${label}: region start /${region.start}/ must match exactly one line of the fixture, matched ${starts.length}`,
    );
  }
  const s = starts[0];
  let e = s;
  if (typeof region.end === "string" && region.end !== "") {
    const endRx = new RegExp(region.end);
    e = -1;
    for (let i = s; i < lines.length; i++) {
      if (endRx.test(lines[i])) {
        e = i;
        break;
      }
    }
    if (e < 0) {
      throw new ScenarioError(`${label}: region end /${region.end}/ not found after its start in the fixture`);
    }
  }
  const prefix = s > 0 ? lines.slice(0, s).join("\n") + "\n" : "";
  const suffix = e + 1 < lines.length ? "\n" + lines.slice(e + 1).join("\n") : "";
  const got = normalizeText(actual);
  if (!got.startsWith(prefix)) {
    return `${label}: content before the allowed region changed (${firstDiffLine(prefix, got)})`;
  }
  if (!got.endsWith(suffix)) {
    return `${label}: content after the allowed region changed or is missing (${suffix.split("\n").length - 1} original line(s) must follow it)`;
  }
  if (got.length < prefix.length + suffix.length) {
    return `${label}: content around the allowed region overlaps (lines were removed)`;
  }
  return null;
}

function firstDiffLine(expectedPrefix, got) {
  const a = expectedPrefix.split("\n");
  const b = got.split("\n");
  for (let i = 0; i < a.length - 1; i++) {
    if (a[i] !== b[i]) return `first difference at line ${i + 1}`;
  }
  return "first difference at the region boundary";
}

function deletePath(obj, dotted) {
  const parts = dotted.split(".");
  let cur = obj;
  for (let i = 0; i < parts.length - 1; i++) {
    if (cur === null || typeof cur !== "object" || !(parts[i] in cur)) return;
    cur = cur[parts[i]];
  }
  if (cur !== null && typeof cur === "object") delete cur[parts[parts.length - 1]];
}

/** Returns the first path where two JSON values differ, or null when they are equal. */
export function jsonDiff(a, b, path = "$") {
  if (a === b) return null;
  if (typeof a !== typeof b || a === null || b === null) return path;
  if (Array.isArray(a) !== Array.isArray(b)) return path;
  if (Array.isArray(a)) {
    if (a.length !== b.length) return `${path} (length ${a.length} vs ${b.length})`;
    for (let i = 0; i < a.length; i++) {
      const d = jsonDiff(a[i], b[i], `${path}[${i}]`);
      if (d) return d;
    }
    return null;
  }
  if (typeof a === "object") {
    const keys = new Set([...Object.keys(a), ...Object.keys(b)]);
    for (const k of [...keys].sort()) {
      if (!(k in a)) return `${path}.${k} (added)`;
      if (!(k in b)) return `${path}.${k} (removed)`;
      const d = jsonDiff(a[k], b[k], `${path}.${k}`);
      if (d) return d;
    }
    return null;
  }
  return path;
}

/** Checks two JSON texts are equal once `allowed` dotted paths are removed from both. */
export function checkJsonExcept(originalText, actualText, allowed, label = "file") {
  if (!Array.isArray(allowed) || allowed.some((p) => typeof p !== "string" || p === "")) {
    throw new ScenarioError(`${label}: json_paths must be an array of dotted paths`);
  }
  let orig;
  try {
    orig = JSON.parse(normalizeText(originalText));
  } catch {
    throw new ScenarioError(`${label}: the fixture copy is not valid JSON`);
  }
  let got;
  try {
    got = JSON.parse(normalizeText(actualText));
  } catch (e) {
    return `${label}: not valid JSON any more (${e instanceof Error ? e.message : String(e)})`;
  }
  for (const p of allowed) {
    deletePath(orig, p);
    deletePath(got, p);
  }
  const d = jsonDiff(orig, got);
  return d ? `${label}: JSON outside ${allowed.join(", ")} changed at ${d}` : null;
}

/** Runs every rule of a scenario. Returns the list of failure reasons (empty = all hold). */
export function checkScenario(scenario, fixtureDir, projectDir) {
  const rules = scenario?.assert?.preserved_except ?? [];
  if (!Array.isArray(rules)) throw new ScenarioError("assert.preserved_except must be an array");
  const reasons = [];
  for (const rule of rules) {
    if (!rule || typeof rule.path !== "string" || rule.path === "") {
      throw new ScenarioError("every preserved_except entry needs a path");
    }
    const hasRegion = rule.region !== undefined;
    const hasJson = rule.json_paths !== undefined;
    if (hasRegion === hasJson) {
      throw new ScenarioError(`${rule.path}: set exactly one of region or json_paths`);
    }
    const orig = join(fixtureDir, rule.path);
    if (!existsSync(orig)) throw new ScenarioError(`${rule.path}: not in the fixture`);
    const actual = join(projectDir, rule.path);
    if (!existsSync(actual)) {
      reasons.push(`${rule.path}: missing after the run`);
      continue;
    }
    const a = readFileSync(orig, "utf8");
    const b = readFileSync(actual, "utf8");
    const r = hasRegion ? checkRegion(a, b, rule.region, rule.path) : checkJsonExcept(a, b, rule.json_paths, rule.path);
    if (r) reasons.push(r);
  }
  return reasons;
}

function main(argv) {
  if (argv.length !== 3) {
    console.log("usage: preserve-check.mjs <scenario.json> <fixtureDir> <projectDir>");
    return 2;
  }
  const [scenarioPath, fixtureDir, projectDir] = argv;
  try {
    const scenario = JSON.parse(readFileSync(scenarioPath, "utf8"));
    const reasons = checkScenario(scenario, fixtureDir, projectDir);
    for (const r of reasons) console.log(r);
    return reasons.length === 0 ? 0 : 1;
  } catch (e) {
    console.log(`preserved_except misconfigured: ${e instanceof Error ? e.message : String(e)}`);
    return 2;
  }
}

// Run as a script only, not when imported by the unit tests. Compared case-insensitively because
// Windows hands the same path over with either drive-letter case.
const self = resolve(fileURLToPath(import.meta.url)).toLowerCase();
if (process.argv[1] && resolve(process.argv[1]).toLowerCase() === self) {
  process.exitCode = main(process.argv.slice(2));
}
