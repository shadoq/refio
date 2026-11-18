# Project rules

This repository is a small receipts library used by several other teams.

- Everything under `api/` is the public surface other teams import. Do not change the signature of any public function in `api/`: no added, removed, renamed or reordered parameters and no changed defaults, not even backwards compatible ones. New behaviour belongs in `core/`; call it from the existing public function.
- Keep `core/` free of imports from `api/`.
- Before you finish, run `python checks/run_checks.py` and make sure it exits 0.
