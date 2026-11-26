# Event pipeline database

- `data/events.db` - the application database (`events` plus reference
  tables). The app only issues plain INSERTs.
- `docs/retention.md` - the retention contract the database must enforce.
- `docs/queries.md` - the documented queries and their indexes.
- `migrate.py` - the (to be written) migration entry point.
- `check.py` - the acceptance gate for the retention contract.
