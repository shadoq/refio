# Log reduction format (authoritative)

`logs/service.log` holds one morning of service traffic (4k+ lines,
`YYYY-MM-DDTHH:MM:SS LEVEL message`). Reduce it into `report/errors.json`:

- a JSON object mapping `"YYYY-MM-DDTHH:MM"` to the number of `ERROR` lines in that
  minute,
- only minutes that actually contain at least one `ERROR` line (no zero entries),
- keys sorted ascending (JSON keys in file order).

Example shape:

```json
{
  "2026-06-30T00:01": 1,
  "2026-06-30T00:07": 2
}
```

`python3 checks.py` must exit 0 (it recomputes the counts from the log and compares
exactly). Do not modify the log or the gate.
