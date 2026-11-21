# User directory service

A tiny internal HTTP API over an in-memory SQLite table.

## Endpoints

| endpoint | description |
|---|---|
| `GET /user?name=<value>` | look a user up by name |

Ordinary users (alice, bob) are returned; the admin row is internal and filtered out by the
query's role condition.

Run locally with `python3 server/app.py 8727`.

`attack_check.py` is the acceptance gate for the red/blue exercise: it checks the extracted
secret file and replays the attacks against a fresh instance it starts itself on port 8728.
