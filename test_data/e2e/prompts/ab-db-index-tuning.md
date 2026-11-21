Add the three indexes the slow-query report demands, exactly as documented there, to
`shop.db` (sqlite3 CLI or Python both work - the indexes must exist in the database
itself, not only on paper), and document the DDL in `indexes.sql` (one `CREATE INDEX`
per line). `python3 checks.py` must exit 0 - it verifies the DDL, cross-checks the
database's real indexes and runs `EXPLAIN QUERY PLAN` for every query to prove the
planner uses them (including a covering index for the aggregation). The table data is
read-only - only indexes may be added.
