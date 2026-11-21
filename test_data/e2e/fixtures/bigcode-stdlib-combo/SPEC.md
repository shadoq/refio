# Activity export summary

`summarize.py` turns a CSV activity export into a JSON summary.

## Input

A CSV file with the header `timestamp,user,action,detail,duration_ms`. The file may start with a UTF-8 byte order mark. Fields can be quoted and can contain commas.

- `timestamp` looks like `2025-03-01T23:30:00-0200` (ISO date and time, then a UTC offset such as `+0000`, `-0200` or `+0530`).
- `user` is a user name. Compare users case-insensitively after stripping spaces.
- `action` is a free text action name. Normalise it by stripping spaces and lowercasing it.
- `detail` is free text. It may mention ticket ids.
- `duration_ms` is an integer number of milliseconds. It can be empty.

## Rows that are skipped

A row is skipped (and counted in `skipped`) when its timestamp cannot be parsed, its user is empty after stripping, or its action is empty after stripping. Skipped rows contribute to nothing else.

## Output

`summarize(path)` returns a dict, and the command line `python summarize.py <input.csv> <output.json>` writes it as JSON with 2-space indent, sorted keys and a trailing newline. Keys:

- `total_events` - number of valid rows.
- `skipped` - number of skipped rows.
- `per_day` - maps a UTC calendar date (`YYYY-MM-DD`) to `{"events": n, "unique_users": n}`. Convert every timestamp to UTC first.
- `top_actions` - the (up to) 3 most frequent normalised actions as a list of `[action, count]` pairs, highest count first, ties broken alphabetically.
- `busiest_hour_utc` - the UTC hour of day (0-23) with the most events, ties broken by the earlier hour; `null` when there are no valid events.
- `tickets` - maps ticket id to the number of valid rows that mention it. A ticket id is two to five uppercase letters, a hyphen and digits (`PAY-7`, `ABC-12`), matched case-sensitively as a whole word. A row counts once per id even if it repeats the id.
- `avg_duration_ms` - maps each normalised action to the average of its durations rounded to 1 decimal place. Empty or non-numeric durations are ignored; a duration of `0` counts. An action with no usable duration does not appear.
