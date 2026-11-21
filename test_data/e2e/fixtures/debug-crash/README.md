# Order dashboard - event helpers

`src/events.py` holds the small event-log helpers used by the dashboard jobs:

- `record_event(event, history)` appends one event to a log and returns it; a fresh log is
  started when none is supplied.
- `take_recent(events, n)` returns and consumes up to the most recent `n` events, oldest
  first.

`test/` holds the unit suite: `python3 -m unittest discover -s test`.
