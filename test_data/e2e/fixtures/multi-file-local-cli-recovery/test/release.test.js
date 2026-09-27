const test = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

const root = path.join(__dirname, "..");

// The release this workspace must publish. Versions, artifacts, checksums and
// dependencies are fixed here on purpose: the check must not trust any file the
// release process writes, including the `verified` flag of the export.
const EXPECTED = {
  base: {
    version: "1.2.0",
    artifact: "artifacts/base-1.2.0.pkg",
    sha256: "a8eaf5e1c50c2926fbd1628d32a32d8d8cef36981add315615f650bb1efa8bd3",
    dependsOn: [],
  },
  ui: {
    version: "2.4.1",
    artifact: "artifacts/ui-2.4.1.pkg",
    sha256: "1f7a8384fd7048316480400dad449e2675cb823b36374776f76e5df40477b4a4",
    dependsOn: ["base"],
  },
  app: {
    version: "3.0.0",
    artifact: "artifacts/app-3.0.0.pkg",
    sha256: "67de810308af26612133585a238d49305afc8f334050d8b7a2379e38c3aa53b5",
    dependsOn: ["base", "ui"],
  },
};

function sha256Of(rel) {
  return crypto.createHash("sha256").update(fs.readFileSync(path.join(root, rel))).digest("hex");
}

function loadRelease() {
  const file = path.join(root, "out", "release.json");
  assert.ok(fs.existsSync(file), "out/release.json was not produced");
  return JSON.parse(fs.readFileSync(file, "utf8"));
}

test("the artifacts on disk still match the release checksums", () => {
  for (const [name, pkg] of Object.entries(EXPECTED)) {
    assert.equal(sha256Of(pkg.artifact), pkg.sha256, `${name} artifact changed`);
  }
});

test("the release document names the manifest release", () => {
  assert.equal(loadRelease().release, "2026.09");
});

test("every package is exported exactly once and nothing else", () => {
  const names = loadRelease().packages.map((p) => p.name);
  assert.deepEqual([...names].sort(), ["app", "base", "ui"]);
});

test("each exported package has its release version, artifact and a recomputed checksum", () => {
  for (const p of loadRelease().packages) {
    const want = EXPECTED[p.name];
    assert.equal(p.version, want.version, `${p.name} version`);
    assert.equal(p.artifact, want.artifact, `${p.name} artifact`);
    assert.equal(p.sha256, want.sha256, `${p.name} checksum`);
    assert.equal(p.sha256, sha256Of(want.artifact), `${p.name} checksum does not match the artifact`);
  }
});

test("dependencies are published before their dependents", () => {
  const order = loadRelease().packages.map((p) => p.name);
  for (const [name, pkg] of Object.entries(EXPECTED)) {
    for (const dep of pkg.dependsOn) {
      assert.ok(order.indexOf(dep) >= 0 && order.indexOf(dep) < order.indexOf(name), `${dep} must come before ${name}`);
    }
  }
});
