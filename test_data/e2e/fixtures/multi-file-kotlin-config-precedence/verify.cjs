"use strict";

// Runs the Gradle test suite offline through the wrapper that fits this OS and fails
// unless the build succeeded and at least one test actually ran without failure.
const { spawnSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const root = __dirname;
const resultsDir = path.join(root, "build", "test-results", "test");
fs.rmSync(resultsDir, { recursive: true, force: true });

const gradleArgs = ["test", "--rerun", "--offline", "--no-daemon", "--console=plain"];
const run =
  process.platform === "win32"
    ? spawnSync("cmd.exe", ["/d", "/c", ".\\gradlew.bat", ...gradleArgs], { cwd: root, stdio: "inherit" })
    : spawnSync("sh", ["./gradlew", ...gradleArgs], { cwd: root, stdio: "inherit" });

if (run.error) {
  console.error(`verify: could not start the Gradle wrapper: ${run.error.message}`);
  process.exit(2);
}
if (run.status !== 0) {
  console.error(`verify: Gradle exited with ${run.status}`);
  process.exit(1);
}

let tests = 0;
let failures = 0;
const files = fs.existsSync(resultsDir)
  ? fs.readdirSync(resultsDir).filter((f) => f.startsWith("TEST-") && f.endsWith(".xml"))
  : [];
for (const f of files) {
  const xml = fs.readFileSync(path.join(resultsDir, f), "utf8");
  const suite = xml.match(/<testsuite\b[^>]*>/);
  if (!suite) continue;
  const attr = (name) => Number((suite[0].match(new RegExp(`\\b${name}="(\\d+)"`)) || [0, 0])[1]);
  tests += attr("tests") - attr("skipped");
  failures += attr("failures") + attr("errors");
}

console.log(`verify: ${tests} test(s) executed, ${failures} failed, ${files.length} result file(s)`);
if (tests <= 0) {
  console.error("verify: no tests were executed");
  process.exit(1);
}
if (failures > 0) process.exit(1);
