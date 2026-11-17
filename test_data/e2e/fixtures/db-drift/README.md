# Migration workspace

- `migrations/` - the chain; `db.py` applies unrecorded files in name order.
- `db.sqlite.drifted` - the incident artifact (read-only): 001+002 applied with data,
  003 recorded but never actually executed.
- `checks.py` - the acceptance gate.

Incident: last night's restore mixed a post-003 `schema_migrations` backup with pre-003
data tables. Production believes it is fully migrated; the `tags` table does not exist.
