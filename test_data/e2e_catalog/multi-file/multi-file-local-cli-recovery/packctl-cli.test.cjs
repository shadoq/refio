// Independent check of the fixture CLI (bin/packctl.cjs) and of the release verifier.
// Not part of the fixture: it proves the tool works as documented before any agent is
// scored against it. Every case runs on a fresh temporary copy of the fixture.
//
// usage (from the repo root): node --test test_data/e2e_catalog/multi-file/multi-file-local-cli-recovery/packctl-cli.test.cjs
const test = require("node:test");
const assert = require("node:assert/strict");
const { spawnSync } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const FIXTURE = path.join(__dirname, "..", "..", "..", "e2e", "fixtures", "multi-file-local-cli-recovery");

const created = [];
test.after(() => {
  for (const dir of created) fs.rmSync(dir, { recursive: true, force: true });
});

function workspace() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "packctl-"));
  created.push(dir);
  fs.cpSync(FIXTURE, dir, { recursive: true });
  return dir;
}

function packctl(ws, ...args) {
  return spawnSync(process.execPath, [path.join(ws, "bin", "packctl.cjs"), ...args], { cwd: ws, encoding: "utf8" });
}

function releaseTest(ws) {
  // Drop the variable node:test sets for its own children, otherwise the nested run
  // reports into this process and always exits 0.
  const env = { ...process.env };
  delete env.NODE_TEST_CONTEXT;
  return spawnSync(process.execPath, ["--test", "test/release.test.js"], { cwd: ws, env, encoding: "utf8" });
}

function registry(ws) {
  return JSON.parse(fs.readFileSync(path.join(ws, ".state", "registry.json"), "utf8"));
}

test("help lists every command and exits 0", () => {
  const r = packctl(workspace(), "--help");
  assert.equal(r.status, 0);
  for (const word of ["reset", "verify", "publish <name>", "export <path>", "Exit codes"]) {
    assert.ok(r.stdout.includes(word), `help misses ${word}`);
  }
});

test("an unknown command is a usage error", () => {
  assert.equal(packctl(workspace(), "deploy").status, 1);
});

test("the seeded registry is reported as stale", () => {
  const r = packctl(workspace(), "verify");
  assert.equal(r.status, 3);
  assert.match(r.stderr, /base: stale/);
  assert.match(r.stderr, /ui: not published/);
});

test("a stale entry cannot be republished without reset", () => {
  assert.equal(packctl(workspace(), "publish", "base").status, 6);
});

test("a package whose dependency is stale is refused", () => {
  assert.equal(packctl(workspace(), "publish", "ui").status, 5);
});

test("an unknown package is refused", () => {
  assert.equal(packctl(workspace(), "publish", "docs").status, 2);
});

test("exporting the seeded state yields a stale document the verifier rejects", () => {
  const ws = workspace();
  assert.equal(packctl(ws, "export", "out/release.json").status, 0);
  const doc = JSON.parse(fs.readFileSync(path.join(ws, "out", "release.json"), "utf8"));
  assert.equal(doc.verified, false);
  assert.deepEqual(doc.packages.map((p) => p.name), ["app", "base"]);
  assert.notEqual(releaseTest(ws).status, 0);
});

test("the untouched fixture fails the release verifier", () => {
  assert.notEqual(releaseTest(workspace()).status, 0);
});

test("the documented flow rebuilds the registry and passes the verifier", () => {
  const ws = workspace();
  assert.equal(packctl(ws, "reset").status, 0);
  assert.deepEqual(registry(ws).published, []);
  assert.equal(packctl(ws, "publish", "app").status, 5, "app before its dependencies must be refused");
  assert.equal(packctl(ws, "publish", "base").status, 0);
  assert.equal(packctl(ws, "publish", "ui").status, 0);
  assert.equal(packctl(ws, "publish", "app").status, 0);
  assert.equal(packctl(ws, "publish", "app").status, 6, "a second publish must be refused");
  assert.equal(packctl(ws, "verify").status, 0);
  assert.equal(packctl(ws, "export", "out/release.json").status, 0);
  const doc = JSON.parse(fs.readFileSync(path.join(ws, "out", "release.json"), "utf8"));
  assert.equal(doc.verified, true);
  assert.deepEqual(doc.packages.map((p) => p.name), ["base", "ui", "app"]);
  const r = releaseTest(ws);
  assert.equal(r.status, 0, r.stdout);
});

test("a tampered artifact is caught by verify and publish", () => {
  const ws = workspace();
  fs.writeFileSync(path.join(ws, "artifacts", "ui-2.4.1.pkg"), "ui package 2.4.1 tampered payload");
  assert.equal(packctl(ws, "reset").status, 0);
  assert.equal(packctl(ws, "verify").status, 4);
  assert.equal(packctl(ws, "publish", "base").status, 0);
  assert.equal(packctl(ws, "publish", "ui").status, 4);
});

test("an out-of-order registry fails verify", () => {
  const ws = workspace();
  const manifest = JSON.parse(fs.readFileSync(path.join(ws, "manifests", "release.json"), "utf8"));
  const entry = (n) => {
    const p = manifest.packages.find((x) => x.name === n);
    return { name: n, version: p.version, sha256: p.sha256 };
  };
  fs.writeFileSync(
    path.join(ws, ".state", "registry.json"),
    JSON.stringify({ release: "2026.09", published: [entry("base"), entry("app"), entry("ui")] }),
  );
  const r = packctl(ws, "verify");
  assert.equal(r.status, 3);
  assert.match(r.stderr, /app: published before its dependency ui/);
});

test("export creates missing parent directories relative to the working directory", () => {
  const ws = workspace();
  assert.equal(packctl(ws, "export", "out/nested/release.json").status, 0);
  assert.ok(fs.existsSync(path.join(ws, "out", "nested", "release.json")));
});
