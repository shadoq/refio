# Orders service

Small SQLite-backed orders store.

- `app/migrate.py` applies the numbered SQL files from `migrations/` in order and records them in `schema_migrations`.
- Run the tests with `python -m unittest discover -s tests -t .`.
- Before you finish, run `python checks/check_schema.py`; it must exit 0.
