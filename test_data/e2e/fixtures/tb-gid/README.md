# Host inventory

- `etc/passwd`, `etc/group` - account and group database (do not modify).
- `srv/` - the service tree whose permission layout is being audited
  (do not modify it either; this is a read-only audit).
- `checks.py` - the acceptance gate (do not modify it).

Produce `report/inventory.txt` as described in the runbook below.

## What to report

- `nologin_count` - accounts whose login shell is exactly `/sbin/nologin`.
  Other non-interactive shells (for instance `/bin/false`) do not count.
- `groups_with_nologin` - the names of the primary groups of those accounts,
  deduplicated and sorted, joined with a comma and a space.
- `files_group_only` - the relative paths (relative to the project root) of
  the files under `srv/` whose permission mode is exactly `0750`, sorted.

Format: three `key = value` lines, exactly those keys and nothing else.
`python3 checks.py` re-derives all three values and compares exactly.
