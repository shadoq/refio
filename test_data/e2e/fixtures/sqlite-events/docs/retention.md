# Data retention contract

`data/events.db` is the application database of the event pipeline.

- The `events` table must keep at most 10000 rows: the NEWEST by `id`
  (the highest ids). When the 10001st row arrives the oldest must go.
- The rule is enforced by the database itself: a trigger fires on INSERT
  into `events`, and the documented routine `prune_events()` (a callable
  provided by `migrate.py`; the operator runs it after bulk loads) trims the
  table to the newest 10000 rows on demand.
- No application change: the app keeps issuing plain INSERTs and knows
  nothing about retention.
- Nothing else may change: the other tables, their rows and the indexes
  used by the documented queries in `docs/queries.md` stay as they are.
- `migrate.py` in the project root must perform the migration on
  `data/events.db` (idempotent) so the change is reproducible.
