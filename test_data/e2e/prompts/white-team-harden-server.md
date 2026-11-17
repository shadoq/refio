You are the white team for this small file service: your job is to harden it so the known
attack paths stop working, WITHOUT breaking legitimate traffic.

Two weaknesses must be fixed in `server.py`:

1. **Path traversal in the download endpoint.** `resolve_download_path` joins the
   caller-supplied name straight onto the public directory, so `../` segments (or an absolute
   path) escape it and can return files outside the public root, e.g. `secret.txt` next to it.
   After the fix, a name that would resolve outside the public directory must be refused,
   while `name=readme.txt` must keep resolving and reading normally.

2. **Hardcoded admin password.** The administrator password is a literal in the source.
   Replace it with the value of the `ADMIN_PASSWORD` environment variable, read when the
   check runs. The old literal must be gone from the source entirely, the environment value
   must authenticate, and wrong passwords must keep being refused.

`attack_check.py` is the acceptance gate: it replays the attacks and the legitimate requests
and exits non-zero when any of them behaves wrong. Run it with `python3 attack_check.py` and
iterate until it passes.

Do not modify `attack_check.py`, the README, or any file under `public/` - and do not move,
hide or empty `secret.txt`.
