# backup.sh contract

`backup.sh <source-dir> <dest-dir>` copies the content of `source-dir` into `dest-dir`.

## What is copied

- every regular file, recursively, with identical content;
- every symbolic link, copied as a link (same target string);
- empty directories are NOT part of the backup content (the manifest only lists files),
  but the destination directory itself must exist after a run;
- other file types (FIFOs, sockets, devices) are skipped with a one-line warning on stderr;
- a file that disappears between the listing and the copy is skipped with a one-line
  warning on stderr naming the file; everything else is still copied.

## Destination handling

- if `dest-dir` does not exist it is created (parents as needed);
- if `dest-dir` exists but is not a directory, exit with code 2;
- if `dest-dir` is not writable (for example a full quota), exit with code 1 and print a
  clear error on stderr.

## Manifest

- after copying, `dest-dir/manifest.sha256` lists one line per copied regular file:
  `<sha256 hex>  <relative path>` (two spaces), sorted by relative path;
- a relative path that contains a newline is written the way sha256sum writes it: the
  line starts with a backslash marker and the newline appears as `\n`;
- symlinks are not listed in the manifest.

## Exit codes

- `0`: every listed file was copied (or there was nothing to copy; an empty source
  directory is a success: the destination is created and the manifest exists but is empty);
- `1`: at least one file could not be copied, the destination was unusable, or a
  vanished file had to be skipped;
- `2`: usage error (wrong number of arguments, source missing or not a directory).

## Idempotency

- a second run over the same pair of directories changes nothing: identical content,
  identical manifest, exit code 0.

## Hygiene

- the script never leaves temporary files behind (use a trap on EXIT);
- it must behave with file names containing spaces, tabs, newlines, glob characters
  (`*`, `?`, `[`), unicode, leading dashes, and with a destination path that contains
  spaces;
- it must not create files in its current working directory under any circumstances.
