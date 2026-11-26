The directory `work/` is the shared drive of a small company. Carry out ALL
of the following instructions, in this order:

1. In the text files under `work/shared/` (recursively; a text file is a
   regular file whose content contains no NUL byte), replace every
   occurrence of the old domain `acme-corp.example` with `acme.example`.
   Do not touch the binary files and leave the existing symlinks under
   `shared/` exactly as they are.
2. Every CSV file in `work/projects/` whose header line `# revision: N`
   has N below 3 is being phased out: write a gzip-compressed snapshot of
   each one into `work/archive/csv/` named `<project>-<filename>.gz`
   (e.g. the `data.csv` of project `acme` becomes
   `work/archive/csv/acme-data.csv.gz`). Keep the originals in place.
3. Write `work/BACKUPS.txt` listing every file whose name ends in `.bak`
   under `work/shared/`, one line per file, in the exact form
   `- <path> (<size> bytes)` where `<path>` is relative to `work/` and
   `<size>` is the file size in bytes. Sort by path ascending.
4. Retire those `.bak` files: truncate each one to zero bytes (keep the
   names and the directory entries, drop the contents).
5. Append the following footer, byte for byte, to the end of every file
   named `notes.md` under `work/projects/` (recursively):

   ```
   --
   status: reviewed 2026-11-25
   ```

   (the block starts with a newline, then `--`, then the status line,
   then a trailing newline).
6. Write `work/INDEX.md` listing every regular file under `work/shared/`
   (recursively; symlinks are not listed), one line per file, in the exact
   form `- <path> (<size> bytes)` with `<path>` relative to `work/` and
   `<size>` the file size in bytes. Sort by size descending, ties broken
   by path ascending.
7. Write `work/COUNT.txt` containing a single line: the total number of
   lines in all `.md` and `.csv` files under `work/projects/`, counted the
   way `wc -l` counts (newline characters).
8. Write `work/MANIFEST.txt` with one line per PDF file under
   `work/invoices/`, in the form `<path> <sha256>` where `<path>` is
   relative to `work/` and `<sha256>` is the lowercase hex SHA-256 digest
   of the file content. Sort by path ascending.

`python3 evaluate.py` (run from the project root) must exit 0. It rebuilds
the expected tree from a pristine copy of the drive and compares paths,
content hashes and symlink targets, so a half-done or improvised tree
fails. Do not modify `evaluate.py`, `README.md` or anything outside
`work/`.
