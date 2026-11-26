# Runbook recovery

`repo/` is the working copy of the service repository. During the incident
export its `.git/objects` was replaced wholesale by the server's object store
backup, so the database contains every object the server ever packed,
including objects no ref points at any more. `history.git.bundle` next to it
is the packed ref layout that was on the server.

The last bad merge rewrote `docs/runbook.md` into a stub. The last good
version, the one whose header line says `revision: 7`, was finished on a
branch that was deleted before anyone merged it, so it is reachable from no
branch or tag. Recover it: restore `docs/runbook.md` in the working tree from
that commit and record which commit it was.

`check.py` re-derives the expected file and commit from the object store
itself and accepts nothing hard-coded.
