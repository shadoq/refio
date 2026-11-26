Audit the tree in this project directory and write
`report/inventory.txt` with exactly three `key = value` lines:

- `nologin_count` - the number of accounts in `etc/passwd` whose login shell
  is exactly `/sbin/nologin` (accounts with `/bin/false` do not count).
- `groups_with_nologin` - the primary group names of those accounts, taken
  from the gid field in `etc/passwd` and the name in `etc/group`,
  deduplicated and sorted, joined with a comma and a space (for instance
  `alpha, beta, gamma`).
- `files_group_only` - the paths of the files under `srv/` whose permission
  mode is exactly `0750`, relative to the project root, sorted, joined with
  a comma and a space.

`python3 checks.py` must exit 0: it re-derives all three values from
`etc/passwd`, `etc/group` and `stat()` of the `srv/` tree and compares
exactly, and it rejects any line that is not a `key = value` line. This is a
read-only audit: do not modify `etc/passwd`, `etc/group`, anything under
`srv/` or the gate.
