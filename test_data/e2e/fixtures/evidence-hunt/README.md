# Server stand-in

A static copy of a small production server's file system, captured for an offline
security review after the March 2026 incident window. The layout mirrors the real
host: `etc/` for configuration, `var/log/` for logs, `home/` for the three users,
`srv/app/` for the in-house metrics exporter source, `usr/local/bin/` for local
tooling and `opt/vendor/` for the backup appliance vendor's support bundle.

Write your findings to `report/evidence.txt` (one `key = value` line per question)
and verify with `python3 check.py`. The review is offline: nothing in this tree is
live, and no answer depends on file timestamps or ownership.
