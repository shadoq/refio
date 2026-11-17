-- 004: "repair" by dropping and recreating the user tables with the tags table alongside.
DROP TABLE IF EXISTS items;
DROP TABLE IF EXISTS tags;
CREATE TABLE items (
    id INTEGER PRIMARY KEY,
    owner TEXT NOT NULL,
    label TEXT NOT NULL
);
CREATE TABLE tags (
    id INTEGER PRIMARY KEY,
    item_id INTEGER NOT NULL,
    tag TEXT NOT NULL
);
