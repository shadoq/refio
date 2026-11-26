# Workstation snapshot: dev-user

Offline copy of a developer home directory captured during the 2026-05
credential exposure drill. One service account API key was shared across
several places in this home (shell history, git config, loose notes, an
old tarball of the staging config and the app settings) instead of being
kept in the secret store. The key has now been revoked on the server
side; this snapshot is what we have to sanitize.

- `home/dev/` - the captured home directory (dotfiles included).
- `home/dev/backup.tar` - the archived staging config tree.
- `check.py` - acceptance gate for the cleanup (run `python3 check.py`).

Scrub the key everywhere it appears and write the inventory to
`report/creds.txt`.
