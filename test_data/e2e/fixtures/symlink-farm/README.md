# Release slot farm

The farm is deployed by the config-management pipeline from `releases/links.txt`:

- `releases/links.txt` - the slot manifest (drifted; see the prompt).
- `releases/versions/` - the built versions, one directory each with its build manifest.
- `docs/release-matrix.md` - the authoritative slot-to-version matrix.
- `checks.py` - the acceptance gate (do not modify it).
