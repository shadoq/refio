Overnight the performance gate on this project went red: `python3 bench.py` on `main`
reports that the lookup operation count is over budget, while the previous release was
green. The incident export packed the repository's `.git` into `history.git.bundle` next
to the working tree (which is checked out at `main`/HEAD).

Your task, in order:

1. Restore the history so git tooling works: run `./restore-history.sh` (it materialises
   `.git` from the bundle; the working tree already matches HEAD), or the equivalent
   `git init && git fetch history.git.bundle main && git reset --mixed FETCH_HEAD`.
2. Find the **first bad commit** - the commit where `bench.py` started failing. The
   intended workflow is `git bisect run ./bisect-test.sh` (mark HEAD bad and the first
   commit good first); any equivalent history analysis is fine as long as the answer is
   right.
3. Write `report/bad-commit.txt` with exactly these two lines:

```
commit=<sha of the first bad commit>
reason=<one line: what that commit did that degraded the lookup>
```

Verify with `python3 check_answer.py` (exit 0 required): it restores the history itself
if needed, re-derives the first bad commit from the history, and matches your report.

Finish with the repository back on `main` at HEAD (run `git bisect reset` if you used
bisect). Do not rewrite history and do not modify `bench.py`, `bisect-test.sh`,
`restore-history.sh`, `history.git.bundle`, `src/index.py`, `check_answer.py` or the
README.
