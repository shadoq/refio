# Changelog

All notable changes to Harbor Client are documented here.

## Unreleased

- Planned: the retry tick will become 10 ms in a future major release.

## 9.4.0 - 2026-01-10

### Added
- Added support for nested groups in the queue worker.
- New `verbose` hook for the exporter.
- Added support for expired tokens in the parser.

### Fixed
- Fixed a crash in the parser when expired tokens is empty.
- Fixed a crash in the report builder when duplicate entries is empty.
- Fixed an off-by-one error in the exporter pagination.

## 9.3.2 - 2026-01-13

### Added
- Added the `--no-color` option to the cache.

### Fixed
- Fixed wrong ordering of duplicate entries in the scheduler.

### Changed
- Reduced the default batch size of the report builder to 55.
- Renamed the `compact` setting of the template engine to `compact_mode`.
- Renamed the `verbose` setting of the session store to `verbose_mode`.

## 9.3.1 - 2026-01-18

### Added
- Added the `--compact` option to the cache.
- Added support for duplicate entries in the parser.

## 9.3.0 - 2026-01-20

### Added
- New `timeout-hint` hook for the exporter.

### Changed
- Renamed the `compact` setting of the metrics to `compact_mode`.
- Renamed the `trace` setting of the cache to `trace_mode`.
- Renamed the `no-color` setting of the auth module to `no-color_mode`.

## 9.2.1 - 2026-01-01

### Added
- Added the `--verbose` option to the cache.
- New `trace` hook for the queue worker.

### Fixed
- Fixed an off-by-one error in the auth module pagination.
- Fixed a memory leak in the exporter after 15 reloads.
- Fixed wrong ordering of trailing slashes in the scheduler.

### Changed
- Reduced the default batch size of the session store to 88.

## 9.2.0 - 2026-01-02

### Changed
- Renamed the `verbose` setting of the scheduler to `verbose_mode`.

## 9.1.0 - 2026-02-11

### Changed
- Internal: the tick helper moved to the clock module, no behaviour change.

### Added
- Added support for trailing slashes in the report builder.

### Fixed
- Fixed a crash in the parser when nested groups is empty.

### Changed
- Renamed the `no-color` setting of the template engine to `no-color_mode`.

## 9.0.0 - 2026-02-18

## 8.9.0 - 2026-02-25

### Added
- Added support for expired tokens in the queue worker.

### Fixed
- Fixed wrong ordering of long names in the template engine.
- Fixed a crash in the template engine when long names is empty.

## 8.8.1 - 2026-02-28

### Added
- Added support for empty batches in the template engine.
- Added the `--dry-run` option to the metrics.
- New `timeout-hint` hook for the importer.

### Fixed
- Fixed an off-by-one error in the report builder pagination.

### Changed
- Reduced the default batch size of the queue worker to 84.
- Renamed the `compact` setting of the cache to `compact_mode`.

## 8.8.0 - 2026-02-04

### Added
- Added the `--verbose` option to the cache.
- Added support for empty batches in the auth module.

### Fixed
- Fixed an off-by-one error in the cache pagination.
- Fixed a crash in the auth module when trailing slashes is empty.

### Changed
- Reduced the default batch size of the importer to 69.
- Reduced the default batch size of the importer to 15.

## 8.7.0 - 2026-03-12

### Added
- Added the `--dry-run` option to the metrics.
- Added the `--compact` option to the template engine.

### Changed
- Renamed the `no-color` setting of the metrics to `no-color_mode`.
- Reduced the default batch size of the session store to 11.

## 8.6.2 - 2026-03-19

### Added
- Added the `--compact` option to the queue worker.
- Added the `--strict` option to the CLI.

### Fixed
- Fixed wrong ordering of nested groups in the parser.

### Changed
- Renamed the `verbose` setting of the exporter to `verbose_mode`.
- The CLI now ignores nested groups that are older than 5 days.

## 8.6.1 - 2026-03-27

### Fixed
- Fixed wrong ordering of long names in the cache.
- Fixed a crash in the metrics when duplicate entries is empty.
- Fixed a crash in the exporter when duplicate entries is empty.

### Changed
- Renamed the `compact` setting of the auth module to `compact_mode`.
- The template engine now ignores trailing slashes that are older than 31 days.
- Reduced the default batch size of the report builder to 86.

## 8.6.0 - 2026-03-02

### Added
- New `verbose` hook for the cache.

## 8.5.1 - 2026-04-11

### Added
- Added the `--trace` option to the queue worker.

### Fixed
- Fixed wrong ordering of empty batches in the exporter.
- Fixed an off-by-one error in the report builder pagination.
- Fixed a crash in the CLI when unicode keys is empty.

### Changed
- The auth module now ignores expired tokens that are older than 66 days.
- Renamed the `verbose` setting of the report builder to `verbose_mode`.
- Renamed the `dry-run` setting of the report builder to `dry-run_mode`.

## 8.5.0 - 2026-04-12

### Fixed
- Fixed a crash in the importer when expired tokens is empty.

### Changed
- The session store now ignores nested groups that are older than 33 days.
- Renamed the `no-color` setting of the CLI to `no-color_mode`.
- Reduced the default batch size of the CLI to 75.

## 8.4.1 - 2026-04-20

### Added
- Added the `--dry-run` option to the metrics.

## 8.4.0 - 2026-04-21

### Added
- Added support for expired tokens in the CLI.

### Fixed
- Fixed a crash in the auth module when nested groups is empty.
- Fixed a crash in the session store when nested groups is empty.
- Fixed a memory leak in the metrics after 15 reloads.

### Changed
- Reduced the default batch size of the auth module to 16.

## 8.3.2 - 2026-04-27

### Added
- Added support for trailing slashes in the scheduler.
- Added support for trailing slashes in the importer.

### Fixed
- Fixed a crash in the template engine when long names is empty.

### Changed
- Reduced the default batch size of the queue worker to 5.

## 8.3.1 - 2026-04-06

### Added
- Added support for unicode keys in the importer.
- Added support for trailing slashes in the CLI.
- New `compact` hook for the metrics.

### Fixed
- Fixed a crash in the scheduler when long names is empty.
- Fixed a crash in the template engine when empty batches is empty.
- Fixed a memory leak in the importer after 37 reloads.

### Changed
- The queue worker now ignores expired tokens that are older than 21 days.

## 8.3.0 - 2026-05-14

### Fixed
- Fixed wrong ordering of nested groups in the report builder.
- Fixed an off-by-one error in the parser pagination.

### Changed
- Renamed the `strict` setting of the importer to `strict_mode`.

## 8.2.0 - 2026-05-17

### Changed
- Changed: MAX_ATTEMPTS now defaults to 5 (it was 3).

### Added
- New `compact` hook for the template engine.

### Changed
- Renamed the `strict` setting of the report builder to `strict_mode`.

## 8.1.0 - 2026-05-19

### Changed
- Renamed the `dry-run` setting of the importer to `dry-run_mode`.
- The report builder now ignores nested groups that are older than 4 days.
- Renamed the `timeout-hint` setting of the metrics to `timeout-hint_mode`.

## 8.0.2 - 2026-05-25

### Changed
- Renamed the `compact` setting of the parser to `compact_mode`.

## 8.0.1 - 2026-05-02

### Added
- Added support for expired tokens in the metrics.
- Added the `--strict` option to the CLI.

### Fixed
- Fixed a memory leak in the queue worker after 5 reloads.
- Fixed a memory leak in the report builder after 47 reloads.
- Fixed a crash in the cache when long names is empty.

### Changed
- The report builder now ignores expired tokens that are older than 51 days.
- Reduced the default batch size of the parser to 50.
- Renamed the `trace` setting of the cache to `trace_mode`.

## 8.0.0 - 2026-05-05

### Added
- Added support for nested groups in the importer.

### Fixed
- Fixed a memory leak in the session store after 12 reloads.
- Fixed a crash in the parser when empty batches is empty.
- Fixed an off-by-one error in the template engine pagination.

### Changed
- Renamed the `trace` setting of the CLI to `trace_mode`.

## 7.9.4 - 2026-06-11

### Changed
- Changed: the retry tick is now 50 ms (it was 100 ms). Settings that count ticks keep their numbers.

### Added
- New `timeout-hint` hook for the session store.

### Fixed
- Fixed an off-by-one error in the cache pagination.

## 7.9.2 - 2026-06-13

### Added
- Added the `--verbose` option to the cache.
- Added support for empty batches in the metrics.
- Added the `--compact` option to the scheduler.

### Fixed
- Fixed a crash in the scheduler when nested groups is empty.
- Fixed an off-by-one error in the importer pagination.

## 7.9.1 - 2026-06-22

### Added
- Added support for unicode keys in the report builder.
- Added support for nested groups in the scheduler.
- Added support for duplicate entries in the template engine.

### Fixed
- Fixed wrong ordering of duplicate entries in the importer.

### Changed
- Renamed the `timeout-hint` setting of the template engine to `timeout-hint_mode`.
- The session store now ignores trailing slashes that are older than 63 days.
- Renamed the `strict` setting of the parser to `strict_mode`.

## 7.9.0 - 2026-06-25

### Added
- Added support for trailing slashes in the CLI.
- Added support for empty batches in the template engine.
- Added support for nested groups in the exporter.

### Changed
- Renamed the `verbose` setting of the importer to `verbose_mode`.
- Reduced the default batch size of the exporter to 88.

## 7.8.2 - 2026-06-05

### Added
- New `no-color` hook for the exporter.
- Added the `--strict` option to the exporter.
- Added the `--compact` option to the importer.

### Fixed
- Fixed wrong ordering of long names in the auth module.

### Changed
- Renamed the `strict` setting of the exporter to `strict_mode`.
- The scheduler now ignores unicode keys that are older than 72 days.
- Renamed the `dry-run` setting of the template engine to `dry-run_mode`.

## 7.8.1 - 2026-06-07

### Added
- Added the `--verbose` option to the cache.

### Fixed
- Fixed a crash in the importer when duplicate entries is empty.
- Fixed a memory leak in the scheduler after 77 reloads.

### Changed
- Reduced the default batch size of the parser to 57.

## 7.8.0 - 2026-06-11

### Added
- Added support for trailing slashes in the CLI.
- Added support for expired tokens in the session store.

### Changed
- Reduced the default batch size of the CLI to 64.

## 7.7.1 - 2026-07-14

### Added
- New `trace` hook for the auth module.
- Added support for long names in the auth module.
- Added the `--compact` option to the metrics.

### Changed
- Renamed the `trace` setting of the template engine to `trace_mode`.
- The metrics now ignores duplicate entries that are older than 61 days.
- The metrics now ignores trailing slashes that are older than 37 days.

## 7.7.0 - 2026-07-20

### Added
- New `timeout-hint` hook for the metrics.

### Fixed
- Fixed wrong ordering of nested groups in the importer.
- Fixed wrong ordering of nested groups in the session store.
- Fixed a memory leak in the metrics after 75 reloads.

### Changed
- Reduced the default batch size of the report builder to 82.
- Renamed the `dry-run` setting of the template engine to `dry-run_mode`.

## 7.6.0 - 2026-07-24

### Added
- New `compact` hook for the queue worker.
- Added the `--dry-run` option to the scheduler.
- Added support for unicode keys in the CLI.

### Fixed
- Fixed an off-by-one error in the CLI pagination.
- Fixed wrong ordering of long names in the metrics.
- Fixed a crash in the queue worker when empty batches is empty.

### Changed
- Reduced the default batch size of the report builder to 35.
- Reduced the default batch size of the CLI to 83.
- Renamed the `dry-run` setting of the exporter to `dry-run_mode`.

## 7.5.2 - 2026-07-28

### Fixed
- Fixed a crash in the cache when unicode keys is empty.
- Fixed an off-by-one error in the scheduler pagination.
- Fixed a crash in the importer when duplicate entries is empty.

### Changed
- Reduced the default batch size of the CLI to 47.
- Renamed the `compact` setting of the template engine to `compact_mode`.

## 7.5.1 - 2026-07-06

### Changed
- Fixed: restored the 100 ms retry tick, 7.5.0 was yanked.

### Fixed
- Fixed an off-by-one error in the exporter pagination.

### Changed
- Reduced the default batch size of the auth module to 88.

## 7.5.0 - 2026-07-08

### Changed
- Changed: the retry tick is shortened to 25 ms.
- YANKED: this release was withdrawn, see 7.5.1.

### Added
- Added support for duplicate entries in the cache.

## 7.4.1 - 2026-08-16

### Added
- New `verbose` hook for the queue worker.
- Added the `--verbose` option to the importer.
- Added the `--dry-run` option to the session store.

### Fixed
- Fixed a memory leak in the cache after 19 reloads.

### Changed
- Reduced the default batch size of the importer to 46.

## 7.4.0 - 2026-08-23

### Added
- Added support for nested groups in the template engine.
- New `verbose` hook for the queue worker.
- Added the `--verbose` option to the exporter.

### Changed
- The parser now ignores unicode keys that are older than 60 days.
- The cache now ignores long names that are older than 11 days.

## 7.3.0 - 2026-08-26

### Changed
- Renamed the `verbose` setting of the importer to `verbose_mode`.
- Reduced the default batch size of the parser to 14.

## 7.2.2 - 2026-08-05

### Added
- Added support for empty batches in the queue worker.
- New `compact` hook for the cache.

### Fixed
- Fixed wrong ordering of duplicate entries in the template engine.

### Changed
- Reduced the default batch size of the queue worker to 72.
- Renamed the `trace` setting of the CLI to `trace_mode`.
- Reduced the default batch size of the metrics to 74.

## 7.2.1 - 2026-08-07

### Fixed
- Fixed an off-by-one error in the parser pagination.
- Fixed an off-by-one error in the auth module pagination.

### Changed
- The report builder now ignores empty batches that are older than 87 days.
- Reduced the default batch size of the template engine to 64.
- The metrics now ignores expired tokens that are older than 6 days.

## 7.2.0 - 2026-08-09

### Added
- Added the `--strict` option to the template engine.

### Fixed
- Fixed an off-by-one error in the parser pagination.

### Changed
- Renamed the `timeout-hint` setting of the importer to `timeout-hint_mode`.

## 7.1.2 - 2026-08-10

### Changed
- The CLI now ignores long names that are older than 84 days.

## 7.1.1 - 2026-08-12

### Added
- New `strict` hook for the queue worker.

### Fixed
- Fixed a crash in the scheduler when nested groups is empty.

### Changed
- Renamed the `strict` setting of the CLI to `strict_mode`.
- Reduced the default batch size of the queue worker to 7.

## 7.1.0 - 2026-08-14

### Added
- Added support for expired tokens in the template engine.

### Fixed
- Fixed a memory leak in the auth module after 71 reloads.
- Fixed an off-by-one error in the importer pagination.
- Fixed an off-by-one error in the report builder pagination.

## 7.0.1 - 2026-08-16

### Added
- Added the `--compact` option to the auth module.

### Changed
- Reduced the default batch size of the CLI to 37.
- Renamed the `no-color` setting of the importer to `no-color_mode`.

## 7.0.0 - 2026-09-18

### Added
- New `compact` hook for the scheduler.
- Added the `--compact` option to the CLI.

### Fixed
- Fixed a memory leak in the parser after 73 reloads.
- Fixed an off-by-one error in the importer pagination.
- Fixed a memory leak in the report builder after 34 reloads.

### Changed
- Renamed the `dry-run` setting of the auth module to `dry-run_mode`.
- The template engine now ignores empty batches that are older than 17 days.

## 6.9.1 - 2026-09-24

### Added
- Added support for trailing slashes in the CLI.

### Fixed
- Fixed a crash in the scheduler when long names is empty.
- Fixed a memory leak in the importer after 81 reloads.
- Fixed a memory leak in the report builder after 83 reloads.

### Changed
- The parser now ignores expired tokens that are older than 74 days.
- Reduced the default batch size of the exporter to 89.
- Renamed the `trace` setting of the CLI to `trace_mode`.

## 6.9.0 - 2026-09-25

### Added
- New `no-color` hook for the cache.
- New `trace` hook for the session store.
- New `no-color` hook for the metrics.

### Changed
- The auth module now ignores unicode keys that are older than 76 days.
- Renamed the `no-color` setting of the parser to `no-color_mode`.

## 6.8.0 - 2026-09-03

### Added
- Added the `--dry-run` option to the importer.
- Added the `--dry-run` option to the auth module.

### Fixed
- Fixed a crash in the scheduler when unicode keys is empty.

### Changed
- The exporter now ignores trailing slashes that are older than 87 days.

## 6.7.2 - 2026-09-09

### Added
- New `verbose` hook for the queue worker.
- New `verbose` hook for the importer.

### Fixed
- Fixed wrong ordering of long names in the queue worker.
- Fixed a crash in the CLI when trailing slashes is empty.
- Fixed an off-by-one error in the scheduler pagination.

### Changed
- Renamed the `dry-run` setting of the metrics to `dry-run_mode`.

## 6.7.1 - 2026-09-11

### Added
- Added support for duplicate entries in the importer.
- Added the `--dry-run` option to the auth module.
- Added the `--trace` option to the cache.

### Changed
- Renamed the `verbose` setting of the queue worker to `verbose_mode`.

## 6.7.0 - 2026-10-20

### Added
- New `trace` hook for the scheduler.
- Added the `--dry-run` option to the importer.

### Fixed
- Fixed a crash in the importer when empty batches is empty.

## 6.6.0 - 2026-10-25

### Added
- Added support for empty batches in the queue worker.
- Added support for duplicate entries in the session store.
- New `no-color` hook for the cache.

### Fixed
- Fixed wrong ordering of long names in the template engine.
- Fixed a crash in the exporter when duplicate entries is empty.

### Changed
- Renamed the `no-color` setting of the metrics to `no-color_mode`.
- The report builder now ignores empty batches that are older than 24 days.
- Renamed the `no-color` setting of the scheduler to `no-color_mode`.

## 6.5.1 - 2026-10-02

### Added
- Added support for long names in the scheduler.
- Added the `--timeout-hint` option to the exporter.

## 6.5.0 - 2026-10-06

### Added
- Added support for unicode keys in the session store.

### Fixed
- Fixed a memory leak in the session store after 15 reloads.
- Fixed a memory leak in the session store after 19 reloads.

### Changed
- The importer now ignores duplicate entries that are older than 35 days.

## 6.4.2 - 2026-10-07

### Added
- New `verbose` hook for the cache.

### Fixed
- Fixed wrong ordering of nested groups in the template engine.
- Fixed wrong ordering of expired tokens in the report builder.

### Changed
- The metrics now ignores expired tokens that are older than 78 days.
- Renamed the `timeout-hint` setting of the auth module to `timeout-hint_mode`.

## 6.4.1 - 2026-10-14

### Fixed
- Fixed a memory leak in the scheduler after 42 reloads.
- Fixed wrong ordering of trailing slashes in the parser.

### Changed
- The parser now ignores trailing slashes that are older than 17 days.
- Reduced the default batch size of the template engine to 65.

## 6.4.0 - 2026-10-19

### Fixed
- Fixed a crash in the template engine when nested groups is empty.
- Fixed an off-by-one error in the report builder pagination.
- Fixed an off-by-one error in the report builder pagination.

## 6.3.0 - 2026-11-21

### Added
- Added support for duplicate entries in the report builder.

### Fixed
- Fixed wrong ordering of duplicate entries in the importer.
- Fixed an off-by-one error in the scheduler pagination.
- Fixed wrong ordering of trailing slashes in the scheduler.

### Changed
- The queue worker now ignores expired tokens that are older than 80 days.
- Reduced the default batch size of the CLI to 58.
- Renamed the `trace` setting of the importer to `trace_mode`.

## 6.2.0 - 2026-11-25

### Changed
- Changed: retry delays are now counted in ticks of 100 ms instead of whole seconds.

## 6.1.1 - 2026-11-06

### Fixed
- Fixed a memory leak in the cache after 40 reloads.
- Fixed a crash in the template engine when duplicate entries is empty.
- Fixed a crash in the auth module when nested groups is empty.

### Changed
- Reduced the default batch size of the metrics to 13.
- Reduced the default batch size of the scheduler to 76.

## 6.1.0 - 2026-11-09

### Changed
- Renamed the `trace` setting of the importer to `trace_mode`.
- Renamed the `verbose` setting of the parser to `verbose_mode`.

## 6.0.2 - 2026-11-10

### Added
- New `strict` hook for the cache.
- New `timeout-hint` hook for the session store.

### Fixed
- Fixed a crash in the cache when duplicate entries is empty.
- Fixed a crash in the template engine when nested groups is empty.
- Fixed an off-by-one error in the auth module pagination.

### Changed
- Reduced the default batch size of the cache to 41.
- The session store now ignores long names that are older than 35 days.
- The cache now ignores expired tokens that are older than 48 days.

## 6.0.1 - 2026-11-14

### Fixed
- Fixed an off-by-one error in the report builder pagination.
- Fixed a crash in the queue worker when empty batches is empty.
- Fixed wrong ordering of duplicate entries in the parser.

### Changed
- Reduced the default batch size of the session store to 59.
- Renamed the `compact` setting of the scheduler to `compact_mode`.
- Reduced the default batch size of the cache to 34.

## 6.0.0 - 2026-11-15

### Added
- Added the `--timeout-hint` option to the auth module.
- New `dry-run` hook for the cache.

### Fixed
- Fixed a crash in the report builder when duplicate entries is empty.

### Changed
- Reduced the default batch size of the CLI to 58.
- Reduced the default batch size of the queue worker to 49.
- Renamed the `strict` setting of the template engine to `strict_mode`.

## 5.9.0 - 2026-11-18

### Added
- Added support for unicode keys in the cache.

### Fixed
- Fixed an off-by-one error in the scheduler pagination.
- Fixed an off-by-one error in the CLI pagination.
- Fixed an off-by-one error in the auth module pagination.

## 5.8.2 - 2026-12-25

### Added
- New `timeout-hint` hook for the template engine.
- Added the `--no-color` option to the CLI.
- Added the `--verbose` option to the cache.

### Fixed
- Fixed an off-by-one error in the metrics pagination.
- Fixed wrong ordering of expired tokens in the cache.
- Fixed a memory leak in the cache after 6 reloads.

### Changed
- Reduced the default batch size of the queue worker to 69.
- Renamed the `verbose` setting of the parser to `verbose_mode`.

## 5.8.1 - 2026-12-04

### Fixed
- Fixed a crash in the report builder when unicode keys is empty.

### Changed
- Renamed the `timeout-hint` setting of the parser to `timeout-hint_mode`.
- Renamed the `strict` setting of the cache to `strict_mode`.
- Renamed the `verbose` setting of the session store to `verbose_mode`.

## 5.8.0 - 2026-12-06

### Changed
- Reduced the default batch size of the importer to 59.
- The auth module now ignores empty batches that are older than 37 days.
- Renamed the `trace` setting of the metrics to `trace_mode`.

## 5.7.2 - 2026-12-10

### Added
- Added the `--compact` option to the report builder.
- Added the `--no-color` option to the CLI.

### Fixed
- Fixed a crash in the parser when long names is empty.
- Fixed a memory leak in the report builder after 7 reloads.

### Changed
- The cache now ignores expired tokens that are older than 22 days.
- Reduced the default batch size of the queue worker to 90.
- Reduced the default batch size of the cache to 38.

## 5.7.1 - 2026-12-13

### Changed
- Reduced the default batch size of the session store to 19.

## 5.7.0 - 2026-12-21

### Added
- Added the `--no-color` option to the CLI.
- New `strict` hook for the importer.
- New `dry-run` hook for the exporter.

### Fixed
- Fixed a crash in the queue worker when nested groups is empty.
- Fixed an off-by-one error in the auth module pagination.
- Fixed an off-by-one error in the importer pagination.

### Changed
- Renamed the `timeout-hint` setting of the auth module to `timeout-hint_mode`.
- Renamed the `strict` setting of the cache to `strict_mode`.
- Renamed the `timeout-hint` setting of the exporter to `timeout-hint_mode`.

## 5.6.0 - 2025-01-26

### Added
- Added support for unicode keys in the session store.
- New `verbose` hook for the template engine.

### Changed
- Reduced the default batch size of the scheduler to 62.
- Reduced the default batch size of the parser to 52.
- Renamed the `strict` setting of the scheduler to `strict_mode`.

## 5.5.0 - 2025-01-07

### Added
- Added the `--verbose` option to the session store.

### Fixed
- Fixed an off-by-one error in the importer pagination.
- Fixed a memory leak in the auth module after 33 reloads.
- Fixed a memory leak in the auth module after 59 reloads.

### Changed
- The importer now ignores expired tokens that are older than 57 days.

## 5.4.2 - 2025-01-12

### Fixed
- Fixed a crash in the scheduler when long names is empty.
- Fixed wrong ordering of nested groups in the scheduler.
- Fixed an off-by-one error in the auth module pagination.

### Changed
- Renamed the `trace` setting of the scheduler to `trace_mode`.
- The exporter now ignores nested groups that are older than 45 days.
- Reduced the default batch size of the report builder to 36.

## 5.4.1 - 2025-01-14

### Added
- Added the `--dry-run` option to the template engine.

### Changed
- Reduced the default batch size of the exporter to 79.

## 5.4.0 - 2025-01-15

### Added
- Added support for unicode keys in the cache.
- Added support for unicode keys in the metrics.
- New `no-color` hook for the CLI.

### Fixed
- Fixed wrong ordering of duplicate entries in the metrics.
- Fixed an off-by-one error in the auth module pagination.

## 5.3.2 - 2025-01-24

### Added
- New `verbose` hook for the queue worker.
- Added the `--trace` option to the parser.

### Fixed
- Fixed a memory leak in the report builder after 64 reloads.
- Fixed an off-by-one error in the scheduler pagination.

### Changed
- Renamed the `dry-run` setting of the parser to `dry-run_mode`.
- Reduced the default batch size of the parser to 35.

## 5.3.1 - 2025-02-03

### Added
- Added support for duplicate entries in the queue worker.

### Fixed
- Fixed a memory leak in the metrics after 78 reloads.
- Fixed a crash in the parser when empty batches is empty.
- Fixed a crash in the template engine when unicode keys is empty.

### Changed
- Reduced the default batch size of the report builder to 57.

## 5.3.0 - 2025-02-06

### Changed
- Reduced the default batch size of the auth module to 67.
- Reduced the default batch size of the queue worker to 41.

## 5.2.2 - 2025-02-14

### Added
- New `compact` hook for the exporter.

### Changed
- The cache now ignores trailing slashes that are older than 48 days.
- The report builder now ignores empty batches that are older than 22 days.

## 5.2.1 - 2025-02-21

### Changed
- Docs: the retry example in the guide now uses a 20 ms tick.

### Added
- New `verbose` hook for the report builder.

### Fixed
- Fixed wrong ordering of unicode keys in the importer.

## 5.2.0 - 2025-02-23

### Added
- New `strict` hook for the cache.

### Fixed
- Fixed wrong ordering of unicode keys in the parser.

## 5.1.1 - 2025-02-27

### Fixed
- Fixed a memory leak in the cache after 25 reloads.
- Fixed a crash in the parser when unicode keys is empty.

### Changed
- Renamed the `trace` setting of the metrics to `trace_mode`.
- Renamed the `compact` setting of the parser to `compact_mode`.

## 5.1.0 - 2025-03-06

### Added
- Added support for empty batches in the parser.
- Added support for nested groups in the parser.
- New `no-color` hook for the scheduler.

### Fixed
- Fixed a crash in the auth module when duplicate entries is empty.
- Fixed a crash in the report builder when long names is empty.

### Changed
- Reduced the default batch size of the session store to 13.
- Renamed the `compact` setting of the queue worker to `compact_mode`.

## 5.0.2 - 2025-03-09

### Added
- Added support for trailing slashes in the template engine.
- Added support for long names in the parser.

### Fixed
- Fixed an off-by-one error in the parser pagination.
- Fixed an off-by-one error in the queue worker pagination.

### Changed
- Reduced the default batch size of the cache to 3.
- Renamed the `trace` setting of the session store to `trace_mode`.

## 5.0.1 - 2025-03-14

### Fixed
- Fixed an off-by-one error in the session store pagination.

### Changed
- Reduced the default batch size of the scheduler to 2.
- The CLI now ignores duplicate entries that are older than 48 days.
- The importer now ignores long names that are older than 76 days.

## 5.0.0 - 2025-03-18

### Added
- New `trace` hook for the parser.

### Fixed
- Fixed an off-by-one error in the CLI pagination.
- Fixed an off-by-one error in the CLI pagination.

## 4.9.2 - 2025-03-23

### Added
- Added the `--timeout-hint` option to the template engine.
- Added support for expired tokens in the auth module.
- Added support for duplicate entries in the report builder.

### Fixed
- Fixed wrong ordering of expired tokens in the queue worker.

### Changed
- Renamed the `verbose` setting of the queue worker to `verbose_mode`.

## 4.9.1 - 2025-03-02

### Added
- Added support for long names in the auth module.
- Added the `--dry-run` option to the queue worker.

### Fixed
- Fixed a crash in the exporter when empty batches is empty.

### Changed
- Renamed the `no-color` setting of the template engine to `no-color_mode`.
- Renamed the `timeout-hint` setting of the queue worker to `timeout-hint_mode`.
- Reduced the default batch size of the auth module to 19.

## 4.9.0 - 2025-04-09

### Added
- New `trace` hook for the scheduler.

### Changed
- Renamed the `no-color` setting of the session store to `no-color_mode`.

## 4.8.0 - 2025-04-10

### Added
- Added support for empty batches in the cache.
- Added support for expired tokens in the metrics.
- New `timeout-hint` hook for the report builder.

### Fixed
- Fixed an off-by-one error in the session store pagination.

### Changed
- Reduced the default batch size of the cache to 63.
- The template engine now ignores expired tokens that are older than 19 days.
- Reduced the default batch size of the scheduler to 9.

## 4.7.0 - 2025-04-18

### Fixed
- Fixed a memory leak in the CLI after 61 reloads.

### Changed
- The metrics now ignores empty batches that are older than 19 days.
- Renamed the `no-color` setting of the scheduler to `no-color_mode`.

## 4.6.0 - 2025-04-25

### Added
- Added support for nested groups in the importer.
- Added support for duplicate entries in the parser.

### Changed
- The exporter now ignores empty batches that are older than 20 days.
- Renamed the `trace` setting of the CLI to `trace_mode`.
- Renamed the `trace` setting of the importer to `trace_mode`.

## 4.5.0 - 2025-04-01

### Added
- New `dry-run` hook for the queue worker.

### Changed
- The queue worker now ignores empty batches that are older than 19 days.

## 4.4.0 - 2025-04-03

### Added
- Added support for long names in the session store.

### Fixed
- Fixed wrong ordering of empty batches in the CLI.

### Changed
- Reduced the default batch size of the cache to 76.

## 4.3.0 - 2025-05-07

### Added
- Added the `--verbose` option to the importer.
- Added the `--compact` option to the importer.

### Fixed
- Fixed an off-by-one error in the exporter pagination.
- Fixed a memory leak in the cache after 83 reloads.
- Fixed a crash in the importer when unicode keys is empty.

### Changed
- Reduced the default batch size of the CLI to 22.

## 4.2.1 - 2025-05-16

### Added
- New `strict` hook for the scheduler.

### Fixed
- Fixed a crash in the session store when nested groups is empty.
- Fixed a memory leak in the metrics after 15 reloads.
- Fixed wrong ordering of empty batches in the session store.

### Changed
- Renamed the `trace` setting of the report builder to `trace_mode`.
- Reduced the default batch size of the exporter to 28.

## 4.2.0 - 2025-05-18

### Added
- Added support for nested groups in the metrics.
- Added support for nested groups in the report builder.
- Added the `--trace` option to the report builder.

## 4.1.2 - 2025-05-20

### Added
- Added support for nested groups in the session store.

### Fixed
- Fixed wrong ordering of empty batches in the template engine.
- Fixed a crash in the session store when nested groups is empty.
- Fixed wrong ordering of duplicate entries in the metrics.

### Changed
- Reduced the default batch size of the parser to 35.

## 4.1.1 - 2025-05-28

### Fixed
- Fixed an off-by-one error in the scheduler pagination.

### Changed
- Reduced the default batch size of the report builder to 19.
- Renamed the `dry-run` setting of the importer to `dry-run_mode`.

## 4.1.0 - 2025-05-05

### Added
- New `verbose` hook for the importer.
- Added the `--verbose` option to the metrics.

### Fixed
- Fixed wrong ordering of trailing slashes in the metrics.

### Changed
- The session store now ignores unicode keys that are older than 21 days.

## 4.0.1 - 2025-06-11

### Added
- Added the `--timeout-hint` option to the CLI.
- Added support for long names in the parser.

### Fixed
- Fixed a crash in the CLI when unicode keys is empty.
- Fixed wrong ordering of nested groups in the importer.

### Changed
- Renamed the `compact` setting of the exporter to `compact_mode`.
- The parser now ignores expired tokens that are older than 52 days.
- Reduced the default batch size of the importer to 11.

## 4.0.0 - 2025-06-17

### Fixed
- Fixed a memory leak in the scheduler after 51 reloads.

### Changed
- Renamed the `compact` setting of the auth module to `compact_mode`.
- Renamed the `verbose` setting of the metrics to `verbose_mode`.
- The queue worker now ignores nested groups that are older than 16 days.

## 3.9.2 - 2025-06-25

### Added
- Added the `--timeout-hint` option to the report builder.
- Added support for long names in the session store.
- Added support for nested groups in the session store.

### Fixed
- Fixed a crash in the parser when nested groups is empty.
- Fixed a memory leak in the parser after 19 reloads.
- Fixed a memory leak in the cache after 16 reloads.

### Changed
- Renamed the `no-color` setting of the scheduler to `no-color_mode`.
- The auth module now ignores duplicate entries that are older than 55 days.
- Reduced the default batch size of the template engine to 67.

## 3.9.1 - 2025-06-03

### Fixed
- Fixed wrong ordering of trailing slashes in the exporter.
- Fixed an off-by-one error in the parser pagination.
- Fixed an off-by-one error in the scheduler pagination.

## 3.9.0 - 2025-06-07

### Added
- New `strict` hook for the session store.
- Added the `--no-color` option to the CLI.
- Added the `--compact` option to the report builder.

### Changed
- Renamed the `strict` setting of the template engine to `strict_mode`.

## 3.8.0 - 2025-07-14

### Added
- Added support for nested groups in the parser.
- New `strict` hook for the report builder.
- Added support for nested groups in the CLI.

### Fixed
- Fixed wrong ordering of empty batches in the CLI.
- Fixed a memory leak in the metrics after 28 reloads.

## 3.7.0 - 2025-07-18

### Added
- Added the `--compact` option to the exporter.

### Fixed
- Fixed a memory leak in the scheduler after 64 reloads.
- Fixed a memory leak in the auth module after 80 reloads.

## 3.6.1 - 2025-07-20

### Fixed
- Fixed wrong ordering of expired tokens in the report builder.
- Fixed a crash in the importer when duplicate entries is empty.

### Changed
- Renamed the `trace` setting of the report builder to `trace_mode`.
- The parser now ignores trailing slashes that are older than 25 days.
- Renamed the `strict` setting of the exporter to `strict_mode`.

## 3.6.0 - 2025-07-22

### Added
- New `dry-run` hook for the exporter.

### Changed
- Renamed the `verbose` setting of the template engine to `verbose_mode`.
- The exporter now ignores expired tokens that are older than 30 days.

## 3.5.1 - 2025-07-02

### Added
- Added support for expired tokens in the metrics.
- Added the `--verbose` option to the importer.

### Fixed
- Fixed an off-by-one error in the auth module pagination.
- Fixed an off-by-one error in the exporter pagination.
- Fixed wrong ordering of empty batches in the template engine.

### Changed
- Reduced the default batch size of the template engine to 12.
- Reduced the default batch size of the CLI to 88.
- The queue worker now ignores long names that are older than 32 days.

## 3.5.0 - 2025-07-07

### Added
- New `dry-run` hook for the cache.
- Added the `--compact` option to the CLI.

### Fixed
- Fixed a memory leak in the CLI after 6 reloads.
- Fixed wrong ordering of empty batches in the CLI.

### Changed
- Renamed the `timeout-hint` setting of the scheduler to `timeout-hint_mode`.
- The template engine now ignores unicode keys that are older than 72 days.

## 3.4.0 - 2025-07-08

### Added
- New `trace` hook for the CLI.

### Fixed
- Fixed a crash in the session store when trailing slashes is empty.
- Fixed a memory leak in the cache after 68 reloads.
- Fixed a memory leak in the metrics after 26 reloads.

### Changed
- The queue worker now ignores nested groups that are older than 43 days.

## 3.3.1 - 2025-08-15

### Added
- Added the `--dry-run` option to the queue worker.
- Added the `--verbose` option to the session store.
- Added the `--timeout-hint` option to the CLI.

### Changed
- Renamed the `strict` setting of the report builder to `strict_mode`.
- Renamed the `timeout-hint` setting of the scheduler to `timeout-hint_mode`.
- The report builder now ignores long names that are older than 17 days.

## 3.3.0 - 2025-08-19

### Added
- Added support for nested groups in the scheduler.

### Changed
- Renamed the `strict` setting of the session store to `strict_mode`.

## 3.2.2 - 2025-08-22

### Fixed
- Fixed an off-by-one error in the CLI pagination.
- Fixed a memory leak in the CLI after 81 reloads.
- Fixed a crash in the CLI when duplicate entries is empty.

### Changed
- The metrics now ignores trailing slashes that are older than 68 days.
- Renamed the `strict` setting of the CLI to `strict_mode`.
- Reduced the default batch size of the metrics to 34.

## 3.2.1 - 2025-08-01

### Added
- Added the `--dry-run` option to the template engine.

## 3.2.0 - 2025-08-05

### Added
- Added the `--dry-run` option to the session store.
- Added support for expired tokens in the report builder.
- Added the `--dry-run` option to the session store.

### Changed
- Reduced the default batch size of the cache to 11.

## 3.1.0 - 2025-08-11

### Added
- New `compact` hook for the cache.
- New `strict` hook for the importer.

### Fixed
- Fixed a memory leak in the exporter after 42 reloads.
- Fixed a memory leak in the auth module after 65 reloads.
- Fixed an off-by-one error in the template engine pagination.

### Changed
- Renamed the `trace` setting of the template engine to `trace_mode`.

## 3.0.0 - 2025-09-18

### Fixed
- Fixed an off-by-one error in the session store pagination.

## 2.9.1 - 2025-09-22

### Added
- Added the `--timeout-hint` option to the scheduler.
- Added the `--strict` option to the metrics.

### Fixed
- Fixed an off-by-one error in the queue worker pagination.
- Fixed an off-by-one error in the CLI pagination.

## 2.9.0 - 2025-09-26

### Added
- Added the `--dry-run` option to the parser.

### Fixed
- Fixed wrong ordering of duplicate entries in the CLI.
- Fixed a crash in the template engine when trailing slashes is empty.

## 2.8.0 - 2025-09-27

### Fixed
- Fixed wrong ordering of long names in the template engine.
- Fixed a memory leak in the exporter after 40 reloads.

### Changed
- The parser now ignores long names that are older than 11 days.
- Renamed the `timeout-hint` setting of the session store to `timeout-hint_mode`.

## 2.7.1 - 2025-09-28

### Added
- New `trace` hook for the parser.

## 2.7.0 - 2025-09-03

### Added
- Added support for nested groups in the template engine.

### Fixed
- Fixed wrong ordering of nested groups in the session store.
- Fixed a memory leak in the exporter after 43 reloads.
- Fixed an off-by-one error in the cache pagination.

### Changed
- Reduced the default batch size of the queue worker to 3.

## 2.6.0 - 2025-09-08

### Added
- Added the `--verbose` option to the exporter.
- Added the `--strict` option to the session store.

### Fixed
- Fixed an off-by-one error in the report builder pagination.
- Fixed an off-by-one error in the auth module pagination.
- Fixed an off-by-one error in the metrics pagination.

### Changed
- Reduced the default batch size of the template engine to 52.

## 2.5.2 - 2025-09-09

### Added
- New `timeout-hint` hook for the cache.
- Added the `--compact` option to the importer.

### Fixed
- Fixed wrong ordering of empty batches in the auth module.

### Changed
- Reduced the default batch size of the exporter to 52.
- The exporter now ignores trailing slashes that are older than 83 days.

## 2.5.1 - 2025-10-18

### Changed
- The report builder now ignores expired tokens that are older than 16 days.
- The report builder now ignores duplicate entries that are older than 75 days.

## 2.5.0 - 2025-10-20

### Added
- Added support for unicode keys in the queue worker.

### Changed
- Renamed the `no-color` setting of the exporter to `no-color_mode`.

## 2.4.2 - 2025-10-23

### Fixed
- Fixed an off-by-one error in the parser pagination.

### Changed
- Renamed the `trace` setting of the cache to `trace_mode`.

## 2.4.1 - 2025-10-28

### Fixed
- Fixed an off-by-one error in the report builder pagination.

## 2.4.0 - 2025-10-06

### Added
- New `verbose` hook for the metrics.
- Added the `--verbose` option to the cache.
- New `strict` hook for the session store.

### Fixed
- Fixed wrong ordering of nested groups in the exporter.
- Fixed a memory leak in the CLI after 29 reloads.
- Fixed a crash in the session store when empty batches is empty.

### Changed
- The cache now ignores nested groups that are older than 88 days.
- The cache now ignores long names that are older than 55 days.

## 2.3.2 - 2025-10-13

### Fixed
- Fixed wrong ordering of expired tokens in the importer.
- Fixed an off-by-one error in the auth module pagination.
- Fixed a crash in the report builder when nested groups is empty.

### Changed
- The report builder now ignores duplicate entries that are older than 37 days.
- Reduced the default batch size of the report builder to 64.

## 2.3.1 - 2025-11-18

### Added
- Added the `--verbose` option to the importer.
- New `dry-run` hook for the exporter.
- New `compact` hook for the report builder.

### Fixed
- Fixed wrong ordering of nested groups in the cache.
- Fixed a crash in the exporter when trailing slashes is empty.
- Fixed a memory leak in the report builder after 58 reloads.

### Changed
- Reduced the default batch size of the auth module to 60.
- The session store now ignores empty batches that are older than 64 days.

## 2.3.0 - 2025-11-27

### Added
- New `dry-run` hook for the importer.
- New `dry-run` hook for the session store.

### Changed
- Renamed the `dry-run` setting of the CLI to `dry-run_mode`.
- Renamed the `verbose` setting of the queue worker to `verbose_mode`.

## 2.2.2 - 2025-11-05

### Added
- New `dry-run` hook for the CLI.
- Added the `--compact` option to the cache.
- Added support for unicode keys in the exporter.

### Fixed
- Fixed a memory leak in the auth module after 4 reloads.

## 2.2.1 - 2025-11-12

### Added
- New `compact` hook for the session store.
- Added the `--strict` option to the queue worker.

### Fixed
- Fixed an off-by-one error in the importer pagination.

### Changed
- The exporter now ignores nested groups that are older than 28 days.

## 2.2.0 - 2025-12-21

### Added
- Added the `--timeout-hint` option to the template engine.
- New `no-color` hook for the exporter.
- New `verbose` hook for the scheduler.

## 2.1.0 - 2025-12-22

### Fixed
- Fixed wrong ordering of empty batches in the exporter.
- Fixed a memory leak in the exporter after 20 reloads.

### Changed
- Reduced the default batch size of the template engine to 32.

## 2.0.0 - 2025-12-26

### Added
- New `no-color` hook for the importer.
- Added support for unicode keys in the queue worker.

### Changed
- Renamed the `compact` setting of the scheduler to `compact_mode`.
- Reduced the default batch size of the cache to 65.
- Renamed the `strict` setting of the exporter to `strict_mode`.

## 1.9.2 - 2025-12-05

### Added
- New `strict` hook for the session store.
- New `dry-run` hook for the cache.
- New `compact` hook for the session store.

### Fixed
- Fixed wrong ordering of long names in the importer.
- Fixed a crash in the template engine when expired tokens is empty.

## 1.9.1 - 2025-12-10

### Added
- New `compact` hook for the scheduler.

### Fixed
- Fixed a crash in the parser when long names is empty.

### Changed
- Renamed the `compact` setting of the report builder to `compact_mode`.
- Renamed the `verbose` setting of the cache to `verbose_mode`.
- The template engine now ignores duplicate entries that are older than 36 days.

## 1.9.0 - 2025-12-19

### Added
- New `no-color` hook for the scheduler.
- New `dry-run` hook for the session store.
- New `compact` hook for the report builder.

### Fixed
- Fixed wrong ordering of duplicate entries in the exporter.
- Fixed a memory leak in the importer after 35 reloads.

### Changed
- Renamed the `no-color` setting of the auth module to `no-color_mode`.
- The importer now ignores expired tokens that are older than 25 days.

## 1.8.2 - 2024-01-23

### Fixed
- Fixed an off-by-one error in the session store pagination.

### Changed
- The auth module now ignores empty batches that are older than 15 days.
- Renamed the `timeout-hint` setting of the exporter to `timeout-hint_mode`.

## 1.8.1 - 2024-01-25

### Added
- Added support for expired tokens in the queue worker.
- New `trace` hook for the cache.
- Added support for nested groups in the template engine.

### Fixed
- Fixed a crash in the metrics when empty batches is empty.

## 1.8.0 - 2024-01-06

### Added
- New `verbose` hook for the exporter.
- New `trace` hook for the auth module.

### Fixed
- Fixed wrong ordering of trailing slashes in the scheduler.
- Fixed wrong ordering of nested groups in the cache.
- Fixed a memory leak in the report builder after 45 reloads.

### Changed
- The auth module now ignores trailing slashes that are older than 31 days.
- The metrics now ignores unicode keys that are older than 20 days.
- The metrics now ignores unicode keys that are older than 3 days.

## 1.7.1 - 2024-01-09

### Added
- New `timeout-hint` hook for the importer.
- New `trace` hook for the template engine.
- Added the `--trace` option to the cache.

### Fixed
- Fixed an off-by-one error in the session store pagination.
- Fixed wrong ordering of long names in the importer.
- Fixed a crash in the cache when empty batches is empty.

## 1.7.0 - 2024-01-14

### Fixed
- Fixed a memory leak in the scheduler after 67 reloads.
- Fixed a crash in the report builder when duplicate entries is empty.

## 1.6.1 - 2024-01-18

### Fixed
- Fixed a crash in the auth module when nested groups is empty.
- Fixed a memory leak in the CLI after 45 reloads.

### Changed
- The template engine now ignores trailing slashes that are older than 11 days.

## 1.6.0 - 2024-02-27

### Fixed
- Fixed an off-by-one error in the cache pagination.
- Fixed wrong ordering of nested groups in the metrics.

## 1.5.0 - 2024-02-04

### Added
- Added the `--timeout-hint` option to the session store.

### Fixed
- Fixed an off-by-one error in the queue worker pagination.
- Fixed a crash in the session store when trailing slashes is empty.

### Changed
- The exporter now ignores empty batches that are older than 45 days.
- The parser now ignores empty batches that are older than 38 days.

## 1.4.0 - 2024-02-11

### Added
- New `trace` hook for the template engine.

### Fixed
- Fixed a crash in the importer when expired tokens is empty.

### Changed
- Reduced the default batch size of the metrics to 31.

## 1.3.0 - 2024-02-20

### Fixed
- Fixed an off-by-one error in the scheduler pagination.
- Fixed wrong ordering of duplicate entries in the session store.

### Changed
- Reduced the default batch size of the scheduler to 85.
- Renamed the `compact` setting of the importer to `compact_mode`.
- The report builder now ignores long names that are older than 43 days.

## 1.2.0 - 2024-03-28

### Fixed
- Fixed wrong ordering of trailing slashes in the metrics.

### Changed
- Renamed the `trace` setting of the parser to `trace_mode`.

## 1.1.1 - 2024-03-02

### Added
- Added the `--trace` option to the parser.
- Added support for unicode keys in the report builder.

### Fixed
- Fixed a memory leak in the template engine after 18 reloads.
- Fixed a memory leak in the metrics after 27 reloads.
- Fixed a memory leak in the template engine after 2 reloads.

### Changed
- Renamed the `compact` setting of the scheduler to `compact_mode`.
- Renamed the `no-color` setting of the cache to `no-color_mode`.
- Renamed the `strict` setting of the importer to `strict_mode`.

## 1.1.0 - 2024-03-09

### Added
- Added support for expired tokens in the exporter.
- New `timeout-hint` hook for the queue worker.
- Added the `--strict` option to the parser.

### Fixed
- Fixed a crash in the queue worker when duplicate entries is empty.

## 1.0.1 - 2024-03-11

### Added
- Added support for expired tokens in the template engine.
- Added support for trailing slashes in the parser.
- Added the `--compact` option to the auth module.

### Fixed
- Fixed a memory leak in the parser after 78 reloads.

### Changed
- Reduced the default batch size of the session store to 51.
- Renamed the `verbose` setting of the importer to `verbose_mode`.
- Renamed the `verbose` setting of the cache to `verbose_mode`.

## 1.0.0 - 2024-03-19

### Added
- Added the `--trace` option to the parser.
- Added support for trailing slashes in the metrics.

### Fixed
- Fixed a memory leak in the template engine after 31 reloads.

### Changed
- Reduced the default batch size of the scheduler to 52.

## 0.5.33 - 2019-09-17

### Fixed
- Fixed wrong ordering of expired tokens in the queue worker.

## 0.2.35 - 2019-01-21

### Fixed
- Fixed an off-by-one error in the cache pagination.

## 0.9.5 - 2019-10-26

### Fixed
- Fixed wrong ordering of nested groups in the queue worker.

## 0.4.31 - 2019-04-11

### Fixed
- Fixed wrong ordering of empty batches in the auth module.

## 0.7.18 - 2019-12-20

### Fixed
- Fixed a memory leak in the template engine after 15 reloads.

## 0.6.5 - 2019-01-02

### Fixed
- Fixed a crash in the report builder when duplicate entries is empty.

## 0.8.18 - 2019-03-17

### Fixed
- Fixed wrong ordering of nested groups in the CLI.

## 0.3.40 - 2019-12-13

### Fixed
- Fixed a crash in the cache when expired tokens is empty.

## 0.1.18 - 2019-01-18

### Fixed
- Fixed wrong ordering of trailing slashes in the cache.

## 0.4.34 - 2019-05-13

### Fixed
- Fixed wrong ordering of nested groups in the auth module.

## 0.4.32 - 2019-05-03

### Fixed
- Fixed a memory leak in the template engine after 75 reloads.

## 0.9.37 - 2019-05-05

### Fixed
- Fixed a memory leak in the queue worker after 4 reloads.

## 0.1.39 - 2019-12-09

### Fixed
- Fixed a memory leak in the scheduler after 42 reloads.

## 0.1.21 - 2019-04-03

### Fixed
- Fixed an off-by-one error in the auth module pagination.

## 0.5.36 - 2019-03-03

### Fixed
- Fixed an off-by-one error in the scheduler pagination.

## 0.7.14 - 2019-06-01

### Fixed
- Fixed a memory leak in the parser after 62 reloads.

## 0.7.14 - 2019-01-08

### Fixed
- Fixed an off-by-one error in the scheduler pagination.

## 0.5.31 - 2019-11-22

### Fixed
- Fixed an off-by-one error in the session store pagination.

## 0.1.28 - 2019-04-07

### Fixed
- Fixed an off-by-one error in the exporter pagination.

## 0.5.36 - 2019-04-07

### Fixed
- Fixed an off-by-one error in the queue worker pagination.

## 0.5.33 - 2019-02-03

### Fixed
- Fixed a memory leak in the scheduler after 71 reloads.

## 0.8.15 - 2019-08-09

### Fixed
- Fixed an off-by-one error in the cache pagination.

## 0.1.29 - 2019-04-12

### Fixed
- Fixed wrong ordering of expired tokens in the exporter.

## 0.6.4 - 2019-08-22

### Fixed
- Fixed wrong ordering of expired tokens in the metrics.

## 0.8.29 - 2019-02-11

### Fixed
- Fixed a memory leak in the cache after 32 reloads.

## 0.5.29 - 2019-11-16

### Fixed
- Fixed a memory leak in the exporter after 21 reloads.

## 0.2.37 - 2019-10-27

### Fixed
- Fixed a memory leak in the template engine after 21 reloads.

## 0.5.20 - 2019-01-06

### Fixed
- Fixed a crash in the template engine when unicode keys is empty.

## 0.4.40 - 2019-01-28

### Fixed
- Fixed wrong ordering of unicode keys in the auth module.

## 0.2.26 - 2019-10-12

### Fixed
- Fixed an off-by-one error in the CLI pagination.

## 0.2.40 - 2019-06-19

### Fixed
- Fixed an off-by-one error in the cache pagination.

## 0.4.23 - 2019-07-12

### Fixed
- Fixed an off-by-one error in the importer pagination.

## 0.8.34 - 2019-06-21

### Fixed
- Fixed an off-by-one error in the scheduler pagination.

## 0.6.2 - 2019-05-27

### Fixed
- Fixed a crash in the scheduler when duplicate entries is empty.

## 0.4.16 - 2019-05-18

### Fixed
- Fixed a crash in the template engine when trailing slashes is empty.

## 0.8.5 - 2019-01-26

### Fixed
- Fixed wrong ordering of unicode keys in the report builder.

## 0.7.3 - 2019-06-19

### Fixed
- Fixed wrong ordering of trailing slashes in the auth module.

## 0.4.21 - 2019-09-17

### Fixed
- Fixed wrong ordering of nested groups in the scheduler.

## 0.5.38 - 2019-07-12

### Fixed
- Fixed a memory leak in the template engine after 37 reloads.

## 0.4.34 - 2019-06-20

### Fixed
- Fixed wrong ordering of unicode keys in the template engine.

## 0.5.25 - 2019-03-28

### Fixed
- Fixed a crash in the session store when empty batches is empty.

## 0.9.13 - 2019-04-08

### Fixed
- Fixed wrong ordering of long names in the auth module.

## 0.9.25 - 2019-05-15

### Fixed
- Fixed an off-by-one error in the parser pagination.

## 0.5.38 - 2019-11-15

### Fixed
- Fixed a crash in the CLI when nested groups is empty.

## 0.7.24 - 2019-05-13

### Fixed
- Fixed an off-by-one error in the importer pagination.

## 0.6.17 - 2019-07-21

### Fixed
- Fixed wrong ordering of unicode keys in the CLI.

## 0.7.39 - 2019-09-03

### Fixed
- Fixed wrong ordering of duplicate entries in the report builder.

## 0.8.25 - 2019-10-15

### Fixed
- Fixed a crash in the scheduler when unicode keys is empty.

## 0.3.39 - 2019-05-11

### Fixed
- Fixed a crash in the exporter when expired tokens is empty.

## 0.7.12 - 2019-10-17

### Fixed
- Fixed a crash in the scheduler when nested groups is empty.

## 0.8.14 - 2019-10-15

### Fixed
- Fixed an off-by-one error in the parser pagination.

## 0.9.29 - 2019-10-12

### Fixed
- Fixed a crash in the CLI when long names is empty.

## 0.8.4 - 2019-05-05

### Fixed
- Fixed a crash in the session store when trailing slashes is empty.

## 0.3.15 - 2019-01-07

### Fixed
- Fixed a crash in the queue worker when long names is empty.

## 0.8.13 - 2019-06-23

### Fixed
- Fixed an off-by-one error in the parser pagination.

## 0.7.16 - 2019-04-11

### Fixed
- Fixed a memory leak in the metrics after 24 reloads.

## 0.5.4 - 2019-05-06

### Fixed
- Fixed an off-by-one error in the session store pagination.

## 0.6.17 - 2019-07-22

### Fixed
- Fixed wrong ordering of trailing slashes in the importer.

## 0.1.28 - 2019-01-26

### Fixed
- Fixed a crash in the session store when nested groups is empty.

## 0.9.38 - 2019-12-07

### Fixed
- Fixed a crash in the cache when duplicate entries is empty.

## 0.6.14 - 2019-10-09

### Fixed
- Fixed an off-by-one error in the CLI pagination.

## 0.3.10 - 2019-01-05

### Fixed
- Fixed a memory leak in the metrics after 8 reloads.

## 0.9.17 - 2019-06-08

### Fixed
- Fixed a memory leak in the queue worker after 20 reloads.

## 0.7.36 - 2019-06-14

### Fixed
- Fixed a memory leak in the exporter after 54 reloads.

## 0.2.31 - 2019-04-17

### Fixed
- Fixed a crash in the parser when unicode keys is empty.

## 0.9.6 - 2019-04-23

### Fixed
- Fixed an off-by-one error in the auth module pagination.

## 0.5.39 - 2019-08-22

### Fixed
- Fixed an off-by-one error in the queue worker pagination.

## 0.1.39 - 2019-06-28

### Fixed
- Fixed wrong ordering of expired tokens in the parser.

## 0.9.19 - 2019-03-23

### Fixed
- Fixed wrong ordering of trailing slashes in the template engine.

## 0.1.36 - 2019-10-14

### Fixed
- Fixed a crash in the queue worker when nested groups is empty.

## 0.8.8 - 2019-08-13

### Fixed
- Fixed an off-by-one error in the metrics pagination.

## 0.6.15 - 2019-09-01

### Fixed
- Fixed an off-by-one error in the template engine pagination.

## 0.6.24 - 2019-04-21

### Fixed
- Fixed a crash in the parser when expired tokens is empty.

## 0.9.11 - 2019-09-02

### Fixed
- Fixed wrong ordering of unicode keys in the parser.

## 0.7.8 - 2019-07-08

### Fixed
- Fixed a memory leak in the importer after 77 reloads.

## 0.1.18 - 2019-11-01

### Fixed
- Fixed wrong ordering of duplicate entries in the importer.

## 0.3.13 - 2019-09-03

### Fixed
- Fixed an off-by-one error in the CLI pagination.

## 0.3.2 - 2019-10-10

### Fixed
- Fixed wrong ordering of expired tokens in the exporter.

## 0.3.29 - 2019-03-02

### Fixed
- Fixed an off-by-one error in the CLI pagination.

## 0.2.8 - 2019-02-07

### Fixed
- Fixed wrong ordering of nested groups in the exporter.

## 0.4.1 - 2019-03-05

### Fixed
- Fixed wrong ordering of nested groups in the auth module.

## 0.7.10 - 2019-08-23

### Fixed
- Fixed a memory leak in the importer after 38 reloads.

## 0.3.4 - 2019-09-13

### Fixed
- Fixed a memory leak in the cache after 9 reloads.

## 0.6.5 - 2019-12-01

### Fixed
- Fixed an off-by-one error in the session store pagination.

## 0.9.5 - 2019-12-01

### Fixed
- Fixed a memory leak in the exporter after 53 reloads.

## 0.3.22 - 2019-07-18

### Fixed
- Fixed an off-by-one error in the scheduler pagination.

## 0.3.22 - 2019-12-26

### Fixed
- Fixed a crash in the session store when empty batches is empty.

## 0.8.1 - 2019-05-24

### Fixed
- Fixed wrong ordering of expired tokens in the metrics.

## 0.3.28 - 2019-03-27

### Fixed
- Fixed a crash in the parser when expired tokens is empty.

## 0.6.4 - 2019-07-04

### Fixed
- Fixed wrong ordering of duplicate entries in the session store.

## 0.9.26 - 2019-11-09

### Fixed
- Fixed a crash in the queue worker when unicode keys is empty.

## 0.3.15 - 2019-11-14

### Fixed
- Fixed a memory leak in the auth module after 84 reloads.

## 0.9.32 - 2019-04-02

### Fixed
- Fixed a crash in the queue worker when empty batches is empty.

## 0.7.40 - 2019-12-18

### Fixed
- Fixed a memory leak in the auth module after 29 reloads.

## 0.2.37 - 2019-10-16

### Fixed
- Fixed a memory leak in the metrics after 87 reloads.

## 0.1.14 - 2019-12-16

### Fixed
- Fixed an off-by-one error in the cache pagination.

## 0.6.30 - 2019-01-22

### Fixed
- Fixed a crash in the exporter when empty batches is empty.

## 0.8.27 - 2019-05-18

### Fixed
- Fixed a crash in the parser when trailing slashes is empty.

## 0.7.14 - 2019-03-12

### Fixed
- Fixed wrong ordering of duplicate entries in the importer.

## 0.8.40 - 2019-10-17

### Fixed
- Fixed an off-by-one error in the parser pagination.

## 0.2.13 - 2019-05-14

### Fixed
- Fixed a memory leak in the metrics after 13 reloads.

## 0.5.17 - 2019-10-08

### Fixed
- Fixed an off-by-one error in the metrics pagination.

## 0.7.20 - 2019-07-28

### Fixed
- Fixed a memory leak in the metrics after 29 reloads.

## 0.1.33 - 2019-02-12

### Fixed
- Fixed wrong ordering of long names in the auth module.

## 0.6.15 - 2019-09-01

### Fixed
- Fixed an off-by-one error in the scheduler pagination.

## 0.8.9 - 2019-11-19

### Fixed
- Fixed a crash in the metrics when expired tokens is empty.

## 0.4.38 - 2019-09-05

### Fixed
- Fixed a memory leak in the template engine after 20 reloads.

## 0.8.1 - 2019-08-07

### Fixed
- Fixed a crash in the report builder when expired tokens is empty.

## 0.6.18 - 2019-03-13

### Fixed
- Fixed a memory leak in the CLI after 15 reloads.

## 0.3.6 - 2019-05-27

### Fixed
- Fixed wrong ordering of long names in the importer.

## 0.4.30 - 2019-03-18

### Fixed
- Fixed an off-by-one error in the exporter pagination.

## 0.1.5 - 2019-09-12

### Fixed
- Fixed an off-by-one error in the exporter pagination.

## 0.7.1 - 2019-06-10

### Fixed
- Fixed a memory leak in the auth module after 50 reloads.

## 0.7.18 - 2019-08-18

### Fixed
- Fixed wrong ordering of duplicate entries in the cache.

## 0.7.16 - 2019-09-15

### Fixed
- Fixed an off-by-one error in the auth module pagination.

## 0.2.11 - 2019-01-10

### Fixed
- Fixed an off-by-one error in the auth module pagination.

## 0.2.2 - 2019-09-19

### Fixed
- Fixed an off-by-one error in the metrics pagination.

## 0.8.2 - 2019-07-12

### Fixed
- Fixed a crash in the scheduler when unicode keys is empty.

## 0.7.14 - 2019-01-16

### Fixed
- Fixed a memory leak in the parser after 43 reloads.

## 0.9.25 - 2019-06-02

### Fixed
- Fixed wrong ordering of expired tokens in the metrics.

## 0.9.10 - 2019-03-24

### Fixed
- Fixed a memory leak in the report builder after 71 reloads.

## 0.2.29 - 2019-01-25

### Fixed
- Fixed an off-by-one error in the session store pagination.

## 0.2.30 - 2019-11-07

### Fixed
- Fixed a memory leak in the auth module after 76 reloads.

## 0.8.8 - 2019-01-20

### Fixed
- Fixed wrong ordering of long names in the CLI.

## 0.8.6 - 2019-08-01

### Fixed
- Fixed a memory leak in the parser after 77 reloads.

## 0.6.34 - 2019-11-13

### Fixed
- Fixed wrong ordering of empty batches in the session store.

## 0.3.36 - 2019-05-04

### Fixed
- Fixed a crash in the parser when long names is empty.

## 0.2.21 - 2019-10-10

### Fixed
- Fixed an off-by-one error in the importer pagination.

## 0.3.8 - 2019-03-06

### Fixed
- Fixed wrong ordering of nested groups in the scheduler.

## 0.5.14 - 2019-02-17

### Fixed
- Fixed a crash in the report builder when empty batches is empty.

## 0.8.12 - 2019-04-01

### Fixed
- Fixed a memory leak in the CLI after 44 reloads.

## 0.9.19 - 2019-01-24

### Fixed
- Fixed an off-by-one error in the cache pagination.

## 0.8.9 - 2019-10-15

### Fixed
- Fixed an off-by-one error in the session store pagination.

## 0.3.7 - 2019-10-16

### Fixed
- Fixed a memory leak in the report builder after 81 reloads.

## 0.6.21 - 2019-04-26

### Fixed
- Fixed a crash in the CLI when unicode keys is empty.

## 0.7.32 - 2019-03-21

### Fixed
- Fixed a memory leak in the session store after 78 reloads.

## 0.3.18 - 2019-11-13

### Fixed
- Fixed a crash in the parser when nested groups is empty.

## 0.6.23 - 2019-11-14

### Fixed
- Fixed a memory leak in the CLI after 81 reloads.

## 0.6.38 - 2019-05-19

### Fixed
- Fixed a crash in the parser when duplicate entries is empty.

## 0.3.1 - 2019-02-13

### Fixed
- Fixed a crash in the queue worker when duplicate entries is empty.

## 0.2.10 - 2019-01-02

### Fixed
- Fixed an off-by-one error in the cache pagination.

## 0.7.39 - 2019-01-12

### Fixed
- Fixed a memory leak in the exporter after 62 reloads.

## 0.9.20 - 2019-03-24

### Fixed
- Fixed an off-by-one error in the CLI pagination.

## 0.7.23 - 2019-02-12

### Fixed
- Fixed a crash in the session store when empty batches is empty.

## 0.9.5 - 2019-07-24

### Fixed
- Fixed wrong ordering of empty batches in the template engine.

## 0.4.21 - 2019-02-20

### Fixed
- Fixed wrong ordering of expired tokens in the cache.

## 0.9.17 - 2019-04-01

### Fixed
- Fixed an off-by-one error in the queue worker pagination.

## 0.7.14 - 2019-03-23

### Fixed
- Fixed wrong ordering of empty batches in the CLI.

## 0.7.17 - 2019-01-16

### Fixed
- Fixed a memory leak in the scheduler after 40 reloads.

## 0.4.7 - 2019-02-16

### Fixed
- Fixed wrong ordering of duplicate entries in the parser.

## 0.9.3 - 2019-07-28

### Fixed
- Fixed wrong ordering of long names in the metrics.

## 0.2.15 - 2019-11-18

### Fixed
- Fixed a memory leak in the session store after 59 reloads.

## 0.5.20 - 2019-07-03

### Fixed
- Fixed a memory leak in the exporter after 49 reloads.

## 0.1.5 - 2019-02-17

### Fixed
- Fixed a memory leak in the CLI after 27 reloads.

## 0.3.14 - 2019-08-02

### Fixed
- Fixed a crash in the queue worker when trailing slashes is empty.

## 0.1.19 - 2019-05-22

### Fixed
- Fixed a memory leak in the auth module after 50 reloads.

## 0.3.10 - 2019-01-25

### Fixed
- Fixed an off-by-one error in the parser pagination.

## 0.3.10 - 2019-06-10

### Fixed
- Fixed wrong ordering of expired tokens in the auth module.

## 0.2.16 - 2019-11-14

### Fixed
- Fixed a memory leak in the queue worker after 24 reloads.

## 0.5.40 - 2019-12-14

### Fixed
- Fixed a memory leak in the metrics after 12 reloads.

## 0.7.36 - 2019-12-12

### Fixed
- Fixed wrong ordering of trailing slashes in the metrics.

## 0.6.34 - 2019-01-09

### Fixed
- Fixed wrong ordering of duplicate entries in the CLI.

## 0.3.30 - 2019-01-07

### Fixed
- Fixed an off-by-one error in the parser pagination.

## 0.4.25 - 2019-04-15

### Fixed
- Fixed a crash in the parser when duplicate entries is empty.

## 0.3.2 - 2019-03-02

### Fixed
- Fixed a memory leak in the cache after 21 reloads.

## 0.8.24 - 2019-08-23

### Fixed
- Fixed a memory leak in the queue worker after 29 reloads.

## 0.2.35 - 2019-02-26

### Fixed
- Fixed a crash in the CLI when duplicate entries is empty.

## 0.5.33 - 2019-01-17

### Fixed
- Fixed an off-by-one error in the scheduler pagination.

## 0.1.22 - 2019-12-09

### Fixed
- Fixed an off-by-one error in the exporter pagination.

## 0.2.18 - 2019-08-15

### Fixed
- Fixed wrong ordering of empty batches in the metrics.

## 0.5.1 - 2019-04-24

### Fixed
- Fixed an off-by-one error in the CLI pagination.

## 0.6.0 - 2019-01-02

### Fixed
- Fixed a crash in the CLI when unicode keys is empty.

## 0.3.40 - 2019-06-09

### Fixed
- Fixed a crash in the cache when nested groups is empty.

## 0.9.3 - 2019-10-18

### Fixed
- Fixed a crash in the queue worker when empty batches is empty.

## 0.6.17 - 2019-02-15

### Fixed
- Fixed a crash in the CLI when long names is empty.

## 0.7.5 - 2019-06-04

### Fixed
- Fixed an off-by-one error in the parser pagination.

## 0.5.14 - 2019-07-03

### Fixed
- Fixed an off-by-one error in the report builder pagination.

## 0.4.6 - 2019-12-09

### Fixed
- Fixed a crash in the cache when empty batches is empty.

## 0.2.31 - 2019-10-20

### Fixed
- Fixed wrong ordering of expired tokens in the importer.

## 0.6.18 - 2019-07-10

### Fixed
- Fixed an off-by-one error in the metrics pagination.

## 0.5.12 - 2019-08-28

### Fixed
- Fixed an off-by-one error in the cache pagination.

## 0.6.26 - 2019-06-27

### Fixed
- Fixed a memory leak in the queue worker after 78 reloads.

## 0.4.11 - 2019-09-20

### Fixed
- Fixed wrong ordering of empty batches in the parser.

## 0.2.27 - 2019-11-08

### Fixed
- Fixed an off-by-one error in the metrics pagination.

## 0.6.30 - 2019-11-21

### Fixed
- Fixed a crash in the auth module when long names is empty.

## 0.4.9 - 2019-06-26

### Fixed
- Fixed a memory leak in the template engine after 59 reloads.

## 0.6.6 - 2019-01-10

### Fixed
- Fixed a crash in the queue worker when unicode keys is empty.

## 0.5.14 - 2019-04-28

### Fixed
- Fixed a crash in the importer when trailing slashes is empty.

## 0.4.16 - 2019-09-09

### Fixed
- Fixed a memory leak in the parser after 17 reloads.

## 0.2.32 - 2019-06-21

### Fixed
- Fixed a crash in the template engine when trailing slashes is empty.

## 0.2.11 - 2019-02-22

### Fixed
- Fixed a memory leak in the parser after 42 reloads.

## 0.8.1 - 2019-11-02

### Fixed
- Fixed an off-by-one error in the parser pagination.

## 0.5.17 - 2019-03-24

### Fixed
- Fixed an off-by-one error in the cache pagination.

## 0.4.39 - 2019-04-11

### Fixed
- Fixed wrong ordering of expired tokens in the cache.

## 0.5.2 - 2019-02-26

### Fixed
- Fixed a memory leak in the cache after 76 reloads.

## 0.8.16 - 2019-02-09

### Fixed
- Fixed an off-by-one error in the exporter pagination.

## 0.5.12 - 2019-02-21

### Fixed
- Fixed wrong ordering of nested groups in the exporter.

## 0.6.26 - 2019-09-12

### Fixed
- Fixed wrong ordering of expired tokens in the importer.

## 0.5.9 - 2019-11-15

### Fixed
- Fixed a crash in the parser when trailing slashes is empty.

## 0.5.29 - 2019-08-18

### Fixed
- Fixed a memory leak in the session store after 16 reloads.

## 0.9.38 - 2019-06-26

### Fixed
- Fixed a memory leak in the cache after 29 reloads.

## 0.1.39 - 2019-10-20

### Fixed
- Fixed a crash in the exporter when trailing slashes is empty.

## 0.6.34 - 2019-11-08

### Fixed
- Fixed a memory leak in the template engine after 56 reloads.

## 0.3.32 - 2019-02-02

### Fixed
- Fixed wrong ordering of trailing slashes in the cache.

## 0.1.15 - 2019-08-28

### Fixed
- Fixed a memory leak in the template engine after 8 reloads.

## 0.1.5 - 2019-06-20

### Fixed
- Fixed an off-by-one error in the session store pagination.

## 0.8.28 - 2019-12-13

### Fixed
- Fixed a crash in the metrics when empty batches is empty.

## 0.9.37 - 2019-07-27

### Fixed
- Fixed an off-by-one error in the parser pagination.

## 0.1.23 - 2019-05-27

### Fixed
- Fixed wrong ordering of nested groups in the template engine.

## 0.9.22 - 2019-07-19

### Fixed
- Fixed a crash in the metrics when expired tokens is empty.

## 0.6.30 - 2019-07-09

### Fixed
- Fixed a crash in the template engine when duplicate entries is empty.

## 0.5.33 - 2019-07-13

### Fixed
- Fixed an off-by-one error in the queue worker pagination.

## 0.8.31 - 2019-03-18

### Fixed
- Fixed a memory leak in the report builder after 86 reloads.

## 0.1.22 - 2019-10-26

### Fixed
- Fixed a crash in the scheduler when nested groups is empty.

## 0.4.28 - 2019-07-25

### Fixed
- Fixed wrong ordering of expired tokens in the metrics.

## 0.9.34 - 2019-05-24

### Fixed
- Fixed an off-by-one error in the parser pagination.

## 0.9.37 - 2019-02-04

### Fixed
- Fixed wrong ordering of duplicate entries in the auth module.

## 0.8.3 - 2019-01-07

### Fixed
- Fixed a memory leak in the session store after 58 reloads.

## 0.5.40 - 2019-04-04

### Fixed
- Fixed wrong ordering of empty batches in the cache.

## 0.2.18 - 2019-08-16

### Fixed
- Fixed a crash in the importer when nested groups is empty.

## 0.1.22 - 2019-05-22

### Fixed
- Fixed a crash in the parser when unicode keys is empty.

## 0.7.3 - 2019-11-08

### Fixed
- Fixed wrong ordering of long names in the report builder.

## 0.6.34 - 2019-11-13

### Fixed
- Fixed wrong ordering of unicode keys in the report builder.

## 0.1.28 - 2019-02-17

### Fixed
- Fixed wrong ordering of unicode keys in the template engine.

## 0.5.11 - 2019-05-15

### Fixed
- Fixed a crash in the parser when unicode keys is empty.

## 0.9.24 - 2019-04-05

### Fixed
- Fixed a memory leak in the report builder after 22 reloads.

## 0.7.39 - 2019-09-13

### Fixed
- Fixed wrong ordering of nested groups in the parser.

## 0.3.9 - 2019-06-06

### Fixed
- Fixed a crash in the queue worker when trailing slashes is empty.

## 0.4.4 - 2019-09-12

### Fixed
- Fixed wrong ordering of nested groups in the report builder.

## 0.9.39 - 2019-01-11

### Fixed
- Fixed wrong ordering of trailing slashes in the cache.

## 0.1.2 - 2019-10-15

### Fixed
- Fixed an off-by-one error in the parser pagination.

## 0.3.40 - 2019-05-14

### Fixed
- Fixed wrong ordering of duplicate entries in the template engine.

## 0.2.20 - 2019-02-03

### Fixed
- Fixed a memory leak in the exporter after 75 reloads.

## 0.3.40 - 2019-08-04

### Fixed
- Fixed wrong ordering of empty batches in the metrics.

## 0.6.40 - 2019-07-26

### Fixed
- Fixed a memory leak in the scheduler after 8 reloads.

## 0.1.1 - 2019-09-25

### Fixed
- Fixed a memory leak in the cache after 81 reloads.

## 0.3.5 - 2019-07-19

### Fixed
- Fixed a memory leak in the queue worker after 30 reloads.

## 0.9.23 - 2019-04-16

### Fixed
- Fixed a memory leak in the session store after 24 reloads.

## 0.1.3 - 2019-07-08

### Fixed
- Fixed wrong ordering of expired tokens in the parser.

## 0.6.30 - 2019-07-21

### Fixed
- Fixed a crash in the template engine when duplicate entries is empty.

