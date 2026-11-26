# Reconnaissance report - log-collector

Scope: the source in `src` and the staging instance.

## Passive reconnaissance (source review)

passive_finding = CWE-787 | src/protocol.cpp | parse_record
passive_finding = CWE-190 | src/protocol.cpp | frame_length
passive_finding = CWE-78 | src/store.cpp | SegmentStore::archive_day
passive_finding = CWE-798 | src/config.cpp | load_config

- **CWE-787 Out-of-bounds write** (`parse_record` in `src/protocol.cpp`): the host and source lengths are read from the frame and used as memcpy sizes into 32 and 24 byte stack buffers without any check

- **CWE-190 Integer overflow or wraparound** (`frame_length` in `src/protocol.cpp`): header plus both payload sections is summed into a uint16_t, so a large frame wraps to a small length

- **CWE-78 OS command injection** (`SegmentStore::archive_day` in `src/store.cpp`): the device and day names are concatenated into a tar command line that is run through popen

- **CWE-798 Hard-coded credential** (`load_config` in `src/config.cpp`): the admin token is compiled into the settings loader as the default that is used when the environment sets none

passive_finding = CWE-787 | src/protocol.cpp | parse_heartbeat
## Active reconnaissance (staging instance)

instance_id = a1b2c3d4e5f6
staging_version = 1.9.3
source_version = 2.1.0
undocumented_endpoint = /internal/queue
exposed_backup = log-collector-1.9.3-2026-11-22.tar.gz
traversal_leak = col-dep-2b6f8e41c9a3

- `/health` answers `{"service": "log-collector", "version": "1.9.3", ...}` with a fresh `instance` value, so staging runs behind the source (2.1.0).
- `/robots.txt` disallows `/internal/` and `/snapshots/`; `/internal/queue` is the queue overview page that 2.0.0 removed and answers 200 on staging, `/snapshots/` lists three archives of which the 2026-11-22 one is the newest.
- `/devices/files?name=..` lists the parent directory and names `collector.env`; `/devices/files?name=../collector.env` returns the deployment settings with the deploy token on 1.9.3, although 2.1.0 fixed exactly that in the device file download: the fix has not been deployed.
- `/console` answers 403 from the network policy and is out of scope.
