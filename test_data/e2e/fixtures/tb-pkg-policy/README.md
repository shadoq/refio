# Fleet package policy snapshot

- `packages.txt` - flat snapshot of candidate package versions, one line per
  version: name, version, origin, component (do not modify).
- `etc/apt/preferences.d/pinning` - the 4 pin stanzas in force (do not
  modify).
- `docs/policy.md` - the installability rules and the report format
  (authoritative).
- `checks.py` - the acceptance gate (do not modify it).

Produce `report/allowed.txt` and `report/denied-count.txt` per
`docs/policy.md`.
