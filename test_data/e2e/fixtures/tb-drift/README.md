# Config drift - environments mirror

- `envs/{dev,staging,prod}/app.json` - the environment configurations (staging drifted).
- `docs/allowed-overrides.json` - the ONLY staging-vs-prod differences that are legal.
- `docs/invariant.md` - the authoritative invariant and its history.
- `checks.py` - the acceptance gate (do not modify it).

Repair `envs/staging/app.json` so the invariant holds again.
