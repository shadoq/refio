`src/lib.rs` is the dashboard's INI-style settings parser. It copes fine with files written
by our own deploy tooling, but it panics the moment anything is off: `parse_section`
unwraps the bracket stripping, `parse_number` expects a valid float, and `get_number`
panics on a missing key. The dashboard now also reads files written by third parties, so a
single malformed line must not take the process down.

Your task:

1. Define a `ParseError` enum in `src/lib.rs` with `InvalidLine`, `MissingKey` and
   `BadNumber` variants, each carrying the offending input (the line, key or value that
   caused the failure); derive the usual traits and implement `Display` (and `Error`) so it
   behaves like a proper library error.
2. Change `parse`, `parse_section`, `parse_pair`, `parse_number`, `get_text` and
   `get_number` to return `Result` with `ParseError`, converting the fallible steps with
   `map_err` / `ok_or` instead of `unwrap`, `expect` and `panic!`.
3. Document on `parse` that it no longer panics - every failure comes back as a
   `ParseError`.
4. Use only the standard library; no external crates.

Do not modify the README: the change belongs in `src/lib.rs`.
