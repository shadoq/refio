# configloader

`config/config.go` holds the key=value loader used by the ingest jobs. The
format is minimal: one `key=value` pair per line, blank lines skipped, and a
`#` starts a comment running to the end of the line. `Load` also enforces the
required keys (`host`, `port`) and a sane port range before returning the
settings map.

Module `github.com/acme/configloader`, Go 1.21. There is no main package
here - the jobs import `config` and call `config.Load(path)`.

Known wart, on the books since the last audit: every error path flattens
whatever went wrong into a plain string, so callers end up matching on
message substrings.
