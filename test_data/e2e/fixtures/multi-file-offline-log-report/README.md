# Request log report

Summarises a JSON Lines request log without any network access. Only Node.js built-ins are used.

```
node bin/report.js --input <file> --output <file>
```

## Input

One JSON object per line. A record is valid when all of these hold:

| Field | Rule |
|---|---|
| `id` | non-empty string |
| `service` | non-empty string |
| `status` | integer from 100 through 599 |
| `latency_ms` | integer, 0 or greater |

- Empty lines (including lines with only whitespace) are ignored and are not counted anywhere.
- A line that is not valid JSON, is not a JSON object, or fails any rule above is counted in `rejected` and otherwise ignored.
- When several valid records share an `id`, the first valid one is kept and each later valid one is counted in `duplicates`. An invalid record never claims an `id`.

## Output

A JSON document:

```json
{
  "services": [
    { "service": "auth", "count": 2, "errors": 1, "p95_ms": 40 }
  ],
  "rejected": 0,
  "duplicates": 0
}
```

- `services` is sorted by `service` name and covers only kept records.
- `count` is the number of kept records for the service.
- `errors` is the number of kept records with `status` 500 or greater.
- `p95_ms` is the nearest-rank 95th percentile of `latency_ms`: sort the latencies ascending and take the value at 1-based rank `ceil(0.95 * count)`.
- Parent directories of the output file are created when missing.

## Exit codes

- `0` - report written.
- `1` - the input file cannot be read. The output file is not created or modified.
- `2` - missing `--input` or `--output`.
