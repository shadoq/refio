The working copy in `repo/` lost its good `docs/runbook.md` to a bad merge.
The server's full git object store was preserved (see `README.md`), and the
last good version of the runbook, the one whose header line says
`revision: 7`, now survives only as a commit that no branch or tag points at.

1. Find that dangling commit in the object store of `repo/`.
2. Restore its `docs/runbook.md` into the working tree at `repo/docs/runbook.md`.
3. Write `repo/recovered-commit.txt` containing exactly the full (40 hex
   character) sha of that commit, one line.

`python3 check.py` (run from the project root) must exit 0: it re-derives the
expected runbook content and commit sha from the object store itself and also
checks that no other tracked file changed. Leave the history and the bundle
untouched; do not create new branches or tags.
