# Dependency scan triage - DepScan export of 2026-10-19

Sources used: `package-lock.json` for the version npm installs (the `overrides` entry
of `package.json` is already applied there), the imports under `src/` followed through
the `dependencies` of each package in the lockfile, and `scan/report.json` for the
ranges and fixed versions.

## Findings

finding = SCA-1012 | true_positive | lockfile installs 1.9.2 (the ^1.9.0 range is not a fix while the lock pins 1.9.2) and src/http/params.js requires it
finding = SCA-1007 | true_positive | lockfile installs 2.1.3, below 2.3.4, and src/export/csv.js imports it
finding = SCA-1003 | true_positive | installed by tabular-csv and query-parse, version 1.2.2 is below 1.3.0
finding = SCA-1021 | true_positive | dom-shim 9.1.0 is inside the vulnerable range and is in package.json
finding = SCA-1018 | true_positive | lockfile installs 0.8.1, inside >=0.5.0 <0.9.0, and src/export/redact.js imports it
finding = SCA-1024 | true_positive | pluralize-rules 3.2.0 is inside the vulnerable range and is in package.json
