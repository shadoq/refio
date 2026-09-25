#!/usr/bin/env node
"use strict";

// packctl - local release registry for this workspace. Works offline only.
// See README.md for the workflow and the exit codes.
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

const WORKSPACE = path.join(__dirname, "..");
const MANIFEST = path.join(WORKSPACE, "manifests", "release.json");
const REGISTRY = path.join(WORKSPACE, ".state", "registry.json");

const EXIT = {
  OK: 0,
  USAGE: 1,
  UNKNOWN_PACKAGE: 2,
  REGISTRY_INVALID: 3,
  CHECKSUM_MISMATCH: 4,
  DEPENDENCY_MISSING: 5,
  ALREADY_PUBLISHED: 6,
};

const HELP = `usage: node bin/packctl.cjs <command> [args]

Commands:
  --help           Show this help.
  reset            Clear the registry for the release in manifests/release.json.
  verify           Check every artifact checksum and the registry against the manifest.
  publish <name>   Verify the artifact of <name> and record it in the registry.
                   Every dependency of <name> must already be published.
  export <path>    Write the registry as a release document to <path>.

Exit codes:
  0 ok, 1 usage error, 2 unknown package, 3 registry does not match the manifest,
  4 artifact checksum mismatch, 5 dependency not published, 6 package already published.
`;

function loadManifest() {
  return JSON.parse(fs.readFileSync(MANIFEST, "utf8"));
}

function loadRegistry(manifest) {
  if (!fs.existsSync(REGISTRY)) return { release: manifest.release, published: [] };
  return JSON.parse(fs.readFileSync(REGISTRY, "utf8"));
}

function saveRegistry(registry) {
  fs.mkdirSync(path.dirname(REGISTRY), { recursive: true });
  fs.writeFileSync(REGISTRY, JSON.stringify(registry, null, 2) + "\n");
}

function sha256Of(relPath) {
  return crypto.createHash("sha256").update(fs.readFileSync(path.join(WORKSPACE, relPath))).digest("hex");
}

function findPackage(manifest, name) {
  return manifest.packages.find((p) => p.name === name);
}

function isCurrent(manifest, entry) {
  const pkg = findPackage(manifest, entry.name);
  return !!pkg && entry.version === pkg.version && entry.sha256 === pkg.sha256;
}

// Returns a list of problems with the registry; empty when it matches the manifest.
function registryProblems(manifest, registry) {
  const problems = [];
  if (registry.release !== manifest.release) {
    problems.push(`registry is for release ${registry.release}, manifest is ${manifest.release}`);
  }
  const position = new Map();
  registry.published.forEach((entry, i) => {
    if (!findPackage(manifest, entry.name)) problems.push(`${entry.name}: not in the manifest`);
    else if (!isCurrent(manifest, entry)) problems.push(`${entry.name}: stale (version ${entry.version})`);
    if (position.has(entry.name)) problems.push(`${entry.name}: published more than once`);
    else position.set(entry.name, i);
  });
  for (const pkg of manifest.packages) {
    if (!position.has(pkg.name)) {
      problems.push(`${pkg.name}: not published`);
      continue;
    }
    for (const dep of pkg.dependsOn) {
      if (!position.has(dep) || position.get(dep) > position.get(pkg.name)) {
        problems.push(`${pkg.name}: published before its dependency ${dep}`);
      }
    }
  }
  return problems;
}

function artifactProblems(manifest) {
  const problems = [];
  for (const pkg of manifest.packages) {
    let actual;
    try {
      actual = sha256Of(pkg.artifact);
    } catch {
      problems.push(`${pkg.name}: artifact ${pkg.artifact} is missing`);
      continue;
    }
    if (actual !== pkg.sha256) problems.push(`${pkg.name}: checksum mismatch for ${pkg.artifact}`);
  }
  return problems;
}

function cmdReset() {
  const manifest = loadManifest();
  saveRegistry({ release: manifest.release, published: [] });
  console.log(`registry reset for release ${manifest.release}`);
  return EXIT.OK;
}

function cmdVerify() {
  const manifest = loadManifest();
  const artifacts = artifactProblems(manifest);
  const registry = registryProblems(manifest, loadRegistry(manifest));
  for (const p of [...artifacts, ...registry]) console.error(`problem: ${p}`);
  if (artifacts.length > 0) return EXIT.CHECKSUM_MISMATCH;
  if (registry.length > 0) return EXIT.REGISTRY_INVALID;
  console.log("verify: all artifacts and the registry match the manifest");
  return EXIT.OK;
}

function cmdPublish(name) {
  if (!name) {
    console.error("publish needs a package name");
    return EXIT.USAGE;
  }
  const manifest = loadManifest();
  const pkg = findPackage(manifest, name);
  if (!pkg) {
    console.error(`unknown package: ${name}`);
    return EXIT.UNKNOWN_PACKAGE;
  }
  let actual;
  try {
    actual = sha256Of(pkg.artifact);
  } catch {
    console.error(`${name}: artifact ${pkg.artifact} is missing`);
    return EXIT.CHECKSUM_MISMATCH;
  }
  if (actual !== pkg.sha256) {
    console.error(`${name}: checksum mismatch for ${pkg.artifact}`);
    return EXIT.CHECKSUM_MISMATCH;
  }
  const registry = loadRegistry(manifest);
  const existing = registry.published.find((e) => e.name === name);
  if (existing) {
    console.error(`${name}: already published (version ${existing.version}); run reset to rebuild the registry`);
    return EXIT.ALREADY_PUBLISHED;
  }
  for (const dep of pkg.dependsOn) {
    const entry = registry.published.find((e) => e.name === dep);
    if (!entry || !isCurrent(manifest, entry)) {
      console.error(`${name}: dependency ${dep} is not published at its manifest version`);
      return EXIT.DEPENDENCY_MISSING;
    }
  }
  registry.published.push({ name, version: pkg.version, sha256: actual });
  saveRegistry(registry);
  console.log(`published ${name} ${pkg.version}`);
  return EXIT.OK;
}

function cmdExport(target) {
  if (!target) {
    console.error("export needs a target path");
    return EXIT.USAGE;
  }
  const manifest = loadManifest();
  const registry = loadRegistry(manifest);
  const doc = {
    release: registry.release,
    packages: registry.published.map((e) => ({
      name: e.name,
      version: e.version,
      artifact: findPackage(manifest, e.name)?.artifact ?? null,
      sha256: e.sha256,
    })),
    verified: registryProblems(manifest, registry).length === 0 && artifactProblems(manifest).length === 0,
  };
  const out = path.resolve(process.cwd(), target);
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, JSON.stringify(doc, null, 2) + "\n");
  console.log(`exported ${doc.packages.length} package(s) to ${target}`);
  return EXIT.OK;
}

function main(argv) {
  const [command, arg] = argv;
  switch (command) {
    case "--help":
    case "-h":
    case "help":
      process.stdout.write(HELP);
      return EXIT.OK;
    case "reset":
      return cmdReset();
    case "verify":
      return cmdVerify();
    case "publish":
      return cmdPublish(arg);
    case "export":
      return cmdExport(arg);
    default:
      process.stderr.write(HELP);
      return EXIT.USAGE;
  }
}

process.exitCode = main(process.argv.slice(2));
