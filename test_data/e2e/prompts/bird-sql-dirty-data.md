`queries.sql` has five questions about our bike rental data, each under a `-- name:` marker with an empty body. Write the SQLite query that answers each question directly under it.

The data comes from a legacy export and is not clean. `DATA_NOTES.md` lists the known quirks (date formats, spacing and case, duplicate rows, unknown values) and the schema is in `build_db.py`. Read both before writing queries.

Check your work with `python check_queries.py`: it must report all five queries correct. It runs the queries on a second, larger database as well, which contains more rows with the same kinds of quirks. Do not edit `check_queries.py`, `build_db.py`, `DATA_NOTES.md` or the CSV files, and keep the `-- name:` and `-- question:` lines as they are.
