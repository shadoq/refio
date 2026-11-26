# Contract: business_days_between

`business_days_between(start, end)` counts whole business days (Monday to Friday)
in the inclusive range `[start, end]`. Bounds may be `datetime.date` or
`datetime.datetime` (the time part is ignored). If `end < start` the result is 0.

Examples (all authoritative):

- 2024-01-08 (Mon) to 2024-01-12 (Fri) = 5
- 2024-01-06 (Sat) to 2024-01-07 (Sun) = 0
- 2024-01-01 (Mon) to 2024-01-14 (Sun) = 10
- 2026-03-06 (Fri) to 2026-03-09 (Mon) = 2 (weekend skipped)
- 2026-03-27 (Fri) to 2026-03-30 (Mon) = 2
