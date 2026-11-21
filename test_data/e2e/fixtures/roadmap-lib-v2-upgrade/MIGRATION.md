# Migrating from recordio 1.x to 2.x

recordio 2.0 replaces the function based API with context managers. The 1.x names no longer exist; importing them fails.

## Reading

1.x:

    rows = read_records(path, delimiter=";")      # list of dicts

2.x:

    with open_reader(path, sep=";", strip=True) as reader:
        rows = [record.as_dict() for record in reader]

- `read_records` always stripped whitespace around every cell. `open_reader` does NOT unless you pass `strip=True`.
- The reader is lazy. A malformed row raises while you iterate, not when the file is opened. Rows read before the bad one have already been seen by your loop. 1.x read the whole file first, so a bad row failed before any row was processed.
- Records are `Record` objects: `record["name"]`, `record.get("name", default)`, `record.as_dict()`, `record.line`.

## Writing

1.x:

    write_records(path, rows, header, delimiter="\t")

2.x:

    with open_writer(path, columns=header, sep="\t") as writer:
        writer.write_all(rows)

- `write_records` silently ignored keys that are not in the header. `writer.write` raises `ParseError` for a key that is not in `columns`, so drop the extra keys yourself.
- Missing keys are still written as empty cells.

## Number parsing

1.x: `parse_int(text, default=0)` and `parse_float(text, default=0.0)` returned the default on any bad input, and the default was 0 / 0.0 when omitted.

2.x: `coerce(value, kind, default=...)`. There is no implicit default: without `default=` a bad value raises `ParseError`. Pass `default=0` (or `0.0`) to get the old behaviour.

## Schemas

1.x: `Schema(["id", "qty"])` and `schema.validate(row)` returned False when a listed field was missing OR empty.

2.x: `Schema.of(id=int, qty=int)` and `schema.apply(raw, line=None)`. It converts every listed field to its type and raises `ParseError` when a field is missing or cannot be converted. An empty string is not treated as missing: for `str` fields `apply` accepts it, so check emptiness yourself if you need it. The result is a new dict.

## Errors

1.x raised `RecordError` with `.row` (1-based number of the data row, not counting the header) and `.message`.

2.x raises `ParseError` with `.line` (physical line number in the file, the header is line 1) and `.message`. A 1.x `row` equals the 2.x `line` minus 1.
