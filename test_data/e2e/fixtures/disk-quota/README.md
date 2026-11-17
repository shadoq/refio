# Disk cleanup run - appdata volume

The volume breached its soft quota. Contents:

- `docs/retention-policy.md` - the authoritative retention policy (reference date
  2026-06-30).
- `data/logs/`, `data/tmp/`, `data/reports/` - the tree the policy applies to (dates are
  in the filenames).
- `checks.py` - the acceptance gate (do not modify it).

The deliverable is the plan manifest, not the deletion: see the policy for its exact
shape.
