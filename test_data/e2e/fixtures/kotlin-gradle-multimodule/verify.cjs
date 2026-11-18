"use strict";

// Runs the Gradle test suites of all modules offline through the wrapper that fits this OS and
// fails unless the build succeeded and every module actually executed tests without failures.
const { spawnSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const root = __dirname;
const modules = ["money", "pricing", "app"];
for (const m of modules) {
  fs.rmSync(path.join(root, m, "build", "test-results", "test"), { recursive: true, force: true });
}

const gradleArgs = ["test", "--rerun", "--offline", "--no-daemon", "--console=plain", "--continue"];
const run =
  process.platform === "win32"
    ? spawnSync("cmd.exe", ["/d", "/c", ".\\gradlew.bat", ...gradleArgs], { cwd: root, stdio: "inherit" })
    : spawnSync("sh", ["./gradlew", ...gradleArgs], { cwd: root, stdio: "inherit" });

if (run.error) {
  console.error(`verify: could not start the Gradle wrapper: ${run.error.message}`);
  process.exit(2);
}

let failed = run.status !== 0;
for (const m of modules) {
  const dir = path.join(root, m, "build", "test-results", "test");
  const files = fs.existsSync(dir) ? fs.readdirSync(dir).filter((f) => f.startsWith("TEST-") && f.endsWith(".xml")) : [];
  let tests = 0;
  let failures = 0;
  for (const f of files) {
    const suite = fs.readFileSync(path.join(dir, f), "utf8").match(/<testsuite\b[^>]*>/);
    if (!suite) continue;
    const attr = (name) => Number((suite[0].match(new RegExp(`\\b${name}="(\\d+)"`)) || [0, 0])[1]);
    tests += attr("tests") - attr("skipped");
    failures += attr("failures") + attr("errors");
  }
  console.log(`verify: ${m}: ${tests} test(s) executed, ${failures} failed`);
  if (tests <= 0 || failures > 0) failed = true;
}
if (failed) {
  console.error("verify: FAILED");
  process.exit(1);
}
console.log("verify: OK");
