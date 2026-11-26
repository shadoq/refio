# ledger-export-api

Small Node.js service that turns ledger entries from the finance ledger into CSV and JSON
exports. It is deployed as a production install (`npm ci --omit=dev`) started with
`npm start`, behind the finance group's gateway.

## Layout

- `src/` - the service: HTTP routing, query parsing, the CSV and JSON exporters.
- `test/` - `node --test` tests and their helpers.
- `tools/` - developer tooling (lint rules), run from the dev machine or the CI lint job.
- `config/` - deployment settings.
- `scan/` - the weekly dependency scan export; see `scan/README.md` for the format.

## Working on it

    npm install
    npm test
    npm run lint
    npm start

Dependency changes go through a pull request: bump the version in `package.json`, let CI run
`npm install` to refresh `package-lock.json`, and attach the triage of the scan export.

## Triage policy

The security group counts a scanner finding against a service only when the vulnerable code
can run in production: the version that the lockfile installs for the production build is
inside the vulnerable range, and the service's own code under `src/` loads the package,
directly or through the dependencies of a package it loads. Findings that fail either half
are closed as false positives with the evidence written down. True positives that are direct
dependencies are bumped to the first fixed version named by the scanner; the lockfile refresh
and the confirmation scan happen in CI.
