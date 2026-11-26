# Dependency scan triage - DepScan export of 2026-10-19

Sources used: `package-lock.json` for the version npm installs (the `overrides` entry
of `package.json` is already applied there), the imports under `src/` followed through
the `dependencies` of each package in the lockfile, and `scan/report.json` for the
ranges and fixed versions.

## Findings

finding = SCA-1012 | false_positive | package.json declares ^1.9.0, which also allows the fixed 1.10.3
finding = SCA-1007 | false_positive | package.json declares ^2.1.0, so npm installs the newest 2.x with the fix
finding = SCA-1003 | false_positive | overrides pins 1.3.0 and the lockfile installs 1.3.0, the vulnerable 1.2.2 is not in the tree
finding = SCA-1021 | false_positive | devDependency used by test/helpers/dom.js only, not installed by npm ci --omit=dev and never imported under src/
finding = SCA-1018 | false_positive | the deny-list is applied to our own field names only, so the bypass cannot be triggered here
finding = SCA-1024 | false_positive | lockfile installs the vulnerable 3.2.0 but nothing under src/ imports it, directly or through a dependency
