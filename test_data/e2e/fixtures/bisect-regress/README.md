# Records index - regression hunt

- `src/index.py` - the records index (hot path of the request handler); the working tree
  is at `main`/HEAD.
- `bench.py` - the performance gate: a fixed workload whose **operation count** inside
  `lookup()` is measured deterministically (line-event counting, no wall clock). Exits 1
  when the budget is exceeded.
- `bisect-test.sh` - the one-line bisect predicate (`python3 bench.py`).
- `history.git.bundle` - the repository's history, packed by the incident export.
- `restore-history.sh` - materialises `.git` from the bundle (idempotent).
- `check_answer.py` - the acceptance gate; it restores the history itself if needed.

Ops paged us: the nightly bench on `main` is red while last week's release was green.
Find the first commit where `bench.py` fails and report it (see the task).
