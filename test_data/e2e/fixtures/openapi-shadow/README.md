# UserDir

A tiny user directory API for the internal demo environment, Python standard library only.

- `server/app.py` - the service. Routes are registered in order in the `ROUTES` list and
  matched first-registered-first-served by `match()`. `GET /api/v2/users` lists users
  (with an optional `dept` filter), `GET /health` is the liveness probe, and a catch-all
  route answers unknown paths with a JSON 404.
- `server/openapi.json` - the published contract; the server serves it at `GET /openapi.json`.
- `tests/api_test.py` - the API suite. Run it with no arguments (it starts its own server
  on a free port) or against a live instance: `python3 tests/api_test.py http://127.0.0.1:8847`.

A partner team reported that the documented `GET /api/v2/users` endpoint "returns a 404
page even though it is in the contract"; the API suite encodes the expected behaviour.
