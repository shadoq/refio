# Changelog

All notable changes to ledger-export-api are documented here.

## 1.4.0 - 2026-10-12

- `src/export/stream.js`: JSON exports are streamed instead of buffered.
- Query filters accept nested objects (`account[id]=12`).

## 1.3.0 - 2026-08-30

- Drop the pluralize-rules import from the CSV header code to shrink the bundle; the
  plural helper now lives in `src/format/labels.js`.
- `npm run lint` uses the finance group's shared style rules.

## 1.2.2 - 2026-07-14

- Dependency hygiene after the platform review of the shared base image.
- Fix a crash in the CSV exporter when an account has no currency.

## 1.2.0 - 2026-06-02

- First release used by the reporting team.
