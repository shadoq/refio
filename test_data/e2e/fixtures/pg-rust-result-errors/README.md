# ini - settings parser

`src/lib.rs` parses the dashboard's INI-style settings files into a flat
`section.key -> value` map. Single-file crate with no external dependencies:
drop it into `src/lib.rs` of any crate, or build it standalone with
`rustc --crate-type lib src/lib.rs`.
