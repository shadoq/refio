# Config converter: v1 (INI-like) to v2 (YAML-like)

`confconv.convert(text: str) -> str` turns a v1 config file into the v2 text format. It is pure text-in, text-out. No third-party packages.

## v1 input

- `[name]` starts a section (`name` is letters, digits, `_`, `.` or `-`).
- `key = value` or `key=value` is a setting; keys have no whitespace.
- Lines starting with `#` or `;` are comments. Blank lines are allowed. Line endings may be `\n` or `\r\n`.

## v2 output

- A section becomes `name:` at column 0, its settings are indented by exactly two spaces as `key: value`.
- Section order, key order and comment order are preserved.

### Values
1. A quoted value (`'text'` or `"text"`) becomes a double-quoted value; backslashes and double quotes inside are escaped with a backslash.
2. An unquoted value that is (case-insensitive) `true`, `yes` or `on` becomes `true`; `false`, `no` or `off` becomes `false`.
3. An empty value becomes `""`.
4. An unquoted value containing `: ` (colon and space) or `#` is written double-quoted (same escaping).
5. Everything else is copied as it is after trimming the surrounding whitespace.

### Comments
- A comment line keeps its text. `#` comments are copied verbatim, `;` comments get `#` instead of `;`.
- Comments inside a section are indented like settings (two spaces). Comments before the first section are not indented.
- A run of comment lines that is directly followed by a `[section]` line (no blank line in between) belongs to that section: it is written at column 0 right above `name:`, even though it appears in the previous section's text.
- An inline comment is a `#` or `;` that is preceded by whitespace and comes after some value text, for example `port = 80 ; web`. It is written after the value, separated by two spaces, with the same `#`/`;` rule: `port: 80  # web`. A `#` or `;` that starts the value (`color = #fff`) is part of the value.
- A quoted value may be followed by an inline comment too; anything else after the closing quote is an error.

### Defaults
After conversion every one of these must be present. Missing settings are appended at the end of an existing section (after its last setting or comment, but before the blank lines and the header comments that belong to the next section). Missing sections are appended at the end of the output, each preceded by one blank line, in the order listed. The default values below are written exactly as shown.

```
server: host=localhost  port=8080  debug=false
db:     pool=5          url=sqlite://memory
log:    level=info
```
Unknown sections and settings are kept as they are.

### Whitespace
- Runs of blank lines collapse to a single blank line; there are no blank lines at the start or the end of the output; the output ends with exactly one `\n`.
- Output always uses `\n`.

### Errors (`ValueError`, message starts with `line N:` where N is the 1-based input line)
- a setting before any section: `line N: setting outside a section`
- a duplicate key in a section: `line N: duplicate key 'KEY' in section 'NAME'`
- a duplicate section: `line N: duplicate section 'NAME'`
- anything else that is not a comment, blank line, section header or `key = value` (including a bad header like `[a b]` or a key with whitespace): `line N: malformed line`
