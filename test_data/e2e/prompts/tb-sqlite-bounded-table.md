`data/events.db` (SQLite) is the event pipeline's database and it keeps
growing. The retention contract in `docs/retention.md` must be implemented
against the database itself:

- `events` holds at most 10000 rows, always the newest by `id`;
- a trigger enforces the cap on INSERT, and the documented `prune_events()`
  routine (callable as `SELECT prune_events();`) trims the table on demand;
- no application change, the other tables, their rows and the documented
  query indexes stay untouched.

Leave `migrate.py` in the project root that performs the migration on
`data/events.db` (idempotent, plain python3 sqlite3, no CLI assumed) and run
it. `python3 check.py` must exit 0: it inserts 20000 fresh rows through the
plain application path, calls the routine, inserts through a fresh connection
and recomputes every expectation from the data. Do not modify `check.py`,
`docs/`, the README or the other tables.
