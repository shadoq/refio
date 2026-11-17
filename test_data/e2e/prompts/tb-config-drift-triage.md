The environments mirror has drifted: `envs/staging/app.json` no longer satisfies the
documented invariant (`docs/invariant.md`) - during the June incident someone hot-patched
a feature flag and it leaked into staging as a bogus block. The legal staging-vs-prod
differences are exactly those in `docs/allowed-overrides.json`; anything else is drift
and must be removed from staging (never by "fixing" prod or dev).

Your task: repair `envs/staging/app.json` so it equals prod plus exactly the allowed
overrides. `python3 checks.py` must exit 0 - it builds prod+overrides and compares
staging key-by-key. Do not modify prod, dev, the docs or the gate.
