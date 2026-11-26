# Documented queries (their indexes must survive)

- `SELECT * FROM events WHERE kind = ? ORDER BY id DESC` uses idx_events_kind.
- `SELECT source, COUNT(*) FROM events GROUP BY source` uses idx_events_source.
