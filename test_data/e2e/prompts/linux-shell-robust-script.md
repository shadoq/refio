The nightly backup wrapper `backup.sh` in this project broke during its last refactor: it
looks right, but it splits file names with spaces, a name starting with a dash is parsed
as an option, the `find` list breaks on newlines in names, the manifest is incomplete,
a hard kill leaves temp directories in `/tmp`, and it does not implement half of the
contract in `docs/backup-spec.md` (exit codes, idempotency, the vanished-file warning).

Fix `backup.sh` so it satisfies `docs/backup-spec.md` exactly. In particular, it must:

- survive file names containing spaces, tabs, newlines, glob characters (`*`, `?`, `[`),
  unicode and leading dashes, and a destination path that contains spaces;
- copy symlinks as symlinks (including dangling ones), skip FIFOs with a warning on
  stderr, and copy an empty source directory as a success (dest created, empty manifest);
- remove a file whose name starts with a dash safely (never as an option);
- warn on stderr and continue when a file disappears between listing and copying, and
  exit 1 for that run;
- create the destination when missing, exit 2 on a usage error (wrong arguments, missing
  source, destination that is not a directory), exit 1 when the destination cannot be
  written (full quota, read-only), 0 otherwise;
- write `manifest.sha256` with one `<sha256>  <relative path>` line per copied regular
  file, sorted by relative path;
- clean up its temp directory with a trap on EXIT, so even a SIGTERM leaves nothing in
  `/tmp`, and never create files in its current working directory;
- be idempotent: a second identical run changes nothing and exits 0.

`tests/cases/make_cases.sh` regenerates the awkward tree the tests use. Verify with
`python3 check.py` and iterate until it passes. Only `backup.sh` may change;
`docs/backup-spec.md`, `tests/`, `README.md` and `check.py` must stay byte-identical.
