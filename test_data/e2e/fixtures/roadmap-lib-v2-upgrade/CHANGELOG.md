# recordio changelog

## 2.0.0

- Breaking: `read_records`, `write_records`, `parse_int`, `parse_float` and `RecordError` are removed. See MIGRATION.md.
- `open_reader` and `open_writer` are context managers and stream records lazily.
- Schemas are typed.

## 1.4.2

- Fix: `read_records` skips blank lines.
