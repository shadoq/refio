# api-edge service repo

`repo/` is the git repository of the api-edge service. Three different old
commits leaked live secrets into `secrets/deploy.key`, `conf/db.conf` and
`conf/api.conf`. The current versions of those files are already clean (they
read from the vault), but the old blobs are still in the object database, so
anyone who clones the repo can `git log` / `git cat-file` the old values
out. All three values were rotated after the leak; the exposure window is
still unacceptable.

Sanitise the repository: after you are done, no object anywhere in
`repo/.git` may contain any of the three leaked values, while the checked
out files, the tracked file list and the README stay exactly as they are.

`check.py` walks every object with `git cat-file --batch-all-objects` and
greps the blobs; it also verifies the tracked files match the golden tree.
