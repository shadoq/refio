Do not modify any files.

Analyze `db-schema.sql`. Describe the entities and their relationships (which table
references which). Then identify the one foreign-key column that is used for lookups but
has no index backing it - the column most likely to cause slow joins or an N+1 pattern.

Name the exact table and column. This is read-only analysis; do not edit the schema.
