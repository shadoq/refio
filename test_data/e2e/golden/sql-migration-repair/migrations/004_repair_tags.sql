-- 004: repair the 003 drift.
-- Production's schema_migrations records 003 as applied, but the tags table never landed
-- (restore mixed a post-003 recording with pre-003 data tables). Re-create the table
-- idempotently: on drifted databases this is the missing 003 effect; on fresh databases
-- 003 already created it and IF NOT EXISTS makes this a no-op.
CREATE TABLE IF NOT EXISTS tags (
    id INTEGER PRIMARY KEY,
    item_id INTEGER NOT NULL,
    tag TEXT NOT NULL
);
