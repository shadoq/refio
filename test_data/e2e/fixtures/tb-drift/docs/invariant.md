# Configuration invariant (authoritative)

`staging/envs` must be production-like: **staging/app.json equals prod/app.json, except
for the overrides explicitly listed in `docs/allowed-overrides.json`** (exact dotted
paths, exact values). Everything else - including any key prod does not have - is drift
and must be removed from staging.

History: during the June incident someone hot-patched the staging feature flag through
the tuning panel and the setting leaked into the mirror as a bogus top-level block. The
flag itself is FALSE in prod and must stay FALSE in staging; the correct repair is to
remove the drift, not to sync prod to staging.

`python3 checks.py` must exit 0 (it loads prod, applies exactly the allowed overrides,
and compares staging key-by-key - extra keys and wrong values both fail). Do not modify
prod, dev, the docs or the gate.
