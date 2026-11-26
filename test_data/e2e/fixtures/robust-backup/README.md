# robust backup

`backup.sh` is the small in-house backup wrapper used by the nightly cron job. It takes a
source directory and a destination directory, copies everything across and writes a
`manifest.sha256` next to the copies. `docs/backup-spec.md` is the contract: exit codes,
manifest layout, idempotency. `tests/cases/make_cases.sh` regenerates the awkward
filename tree we test against (spaces, newlines, glob characters, unicode, leading
dashes, symlinks, a FIFO, an empty directory, a deeply nested path).

The script broke during the last refactor: real file names with spaces split mid-copy,
a name starting with a dash is parsed as an option, the manifest is incomplete, and a
hard kill leaves temp files in /tmp. Fix it to the spec; `check.py` drives the script
against generated trees and failure modes and must pass.
