Production has schema drift: last night's restore mixed a `schema_migrations` backup taken
*after* migration 003 was recorded with data tables from *before* it ran. This project is
the service's migration workspace:

- `migrations/` - the migration chain (`db.py` applies them in name order and records
  them). `003_add_tags.sql` is what production *thinks* it already ran.
- `db.sqlite.drifted` - a byte copy of the drifted production database (001 and 002
  applied with their data; 003 recorded in `schema_migrations` but its table never
  landed). Treat it as the incident artifact: read-only.
- `db.py` - the migration runner (`python3 db.py <dbfile> migrate`).
- `checks.py` - the acceptance gate (do not modify it).
- `README.md` - incident notes.

Your task: repair the drift **through the migration chain** - add a new migration
(`004_repair_...sql`) that brings any database whose history says 001-003 ran to the full
intended schema, while:

- a **fresh** database (`migrate` on an empty file) still applies the whole chain
  cleanly, including your repair, ending in the same schema;
- the drifted database's existing rows survive the repair (no dropping and recreating
  user tables);
- running `migrate` twice converges (second run is a no-op).

The 001-003 files, the drifted snapshot and the runner stay byte-identical.
`python3 checks.py` must exit 0.
