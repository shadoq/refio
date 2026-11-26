The git repository in `repo/` has three leaked secrets in
its history (in `secrets/deploy.key`, `conf/db.conf` and `conf/api.conf`,
three different old commits). The current versions of those files are
already clean, but the old blobs are still in the object database.

Rewrite the history of `repo/` so that:

1. no object anywhere in `repo/.git` (reachable or not, packed or loose,
   including reflogs, backup refs and any leftover pack) contains any of
   the three leaked values (`DEPLOY-KEY-9f3a71cd`, `DB-PASSWORD-wq88z2`,
   `API-TOKEN-7310ab`) or any fragment of them,
2. every tracked file stays byte-identical: the tracked file list, the
   checked out content of every file and `README.md` are unchanged,
3. the working tree is clean at the end (no dirty state).

`python3 check.py` (from the project root) must exit 0: it walks every
object via `git cat-file --batch-all-objects --batch-check`, greps all
blobs for the three markers and their fragments, and verifies the tracked
files against the golden tree. Note: git is not in the default
auto-approve set, so the run must be started with git in `--auto-approve`.
