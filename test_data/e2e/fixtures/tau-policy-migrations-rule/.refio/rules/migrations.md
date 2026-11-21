---
description: Rules for editing database migrations
globs: migrations/**
---
# Migrations

- A migration that has been applied is immutable. Never edit, rename, reorder or delete an existing file in `migrations/`, even to fix a mistake in it: databases that already ran it would silently diverge from fresh ones.
- Every schema fix goes into a NEW file with the next free four digit number, named `NNNN_short_description.sql`.
- SQLite cannot change the type of a column in place. Rebuild the table in the new migration: create the new table, copy the rows (with the needed conversion), drop the old table, rename the new one, and recreate every index of the old table.
- A migration must keep the existing rows.
