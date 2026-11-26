# Changelog

## 2.1.0 (2026-11-16)

- Security: the device file download now refuses names that leave the spool directory. Reported
  through the responsible disclosure address by an external researcher.
- Segments are flushed every 5 seconds instead of every 30.

## 2.0.0 (2026-10-27)

- Security: the queue overview page that printed the process environment was removed.
  Operations reads the same values from the deployment platform now.
- Security: the upgrade path respawns the collector with an argument array instead of a command
  line.
- Breaking: records use wire format version 2.

## 1.9.3 (2026-10-10)

- Last release before the 2.x line.
- Daily segment files per device.
- Nightly archives of closed days.

## 1.9.0 (2026-09-25)

- Initial release with the TCP ingest and the heartbeat.
