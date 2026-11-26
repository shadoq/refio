# SLO and error budget (authoritative)

`logs/access.log` is nginx combined format:

```
$client - $remote_user [$time_local] "$request" $status $body_bytes_sent "$referer" "$user_agent"
```

The monthly edge SLO review needs the following numbers, derived from the log
only:

- `total_requests` - every parsed request line.
- `http_5xx` - responses with status 500 to 599.
- `five_x_ratio` - `http_5xx / total_requests`, written with exactly 3
  decimals (for instance `0.068`).
- `http_499` - responses with status 499 (client closed early). The status
  field is authoritative; byte counts are not statuses.
- `unique_clients_5xx` - how many distinct client addresses saw at least one
  5xx response.
- `top_5xx_client` - the client address with the most 5xx responses.

Write them to `report/error-budget.txt` as `key = value` lines, one per line,
exactly those six keys and nothing else. `python3 checks.py` must exit 0: it
re-derives all six numbers from the log and compares exactly, and it also
rejects any line that is not a `key = value` line. Do not modify the log or
the gate.
