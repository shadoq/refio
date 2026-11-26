# Shadowed endpoint in UserDir: routing fix report

shadowed_endpoint = /api/v2/users

catchall_position = first

fixed_by = reordering ROUTES so every exact route is registered before the /{path} catch-all

## What was wrong

`server/app.py` matches routes with `match()` over the `ROUTES` list, first-registered-
first-served. The legacy catch-all `("GET", "/{path}", not_found)` was registered at the
TOP of the list, before `("GET", "/api/v2/users", list_users)`. Because the catch-all
pattern `/{path}` matches every path, it consumed every request, and the documented
`GET /api/v2/users` (and `/health` and `/openapi.json`) could never be reached: callers
got the catch-all JSON 404 page for an endpoint that `openapi.json` documents as returning
the user list.

## The fix

The `ROUTES` list now registers the exact routes first and the catch-all last:

- `("GET", "/api/v2/users", list_users)`
- `("GET", "/health", health)`
- `("GET", "/openapi.json", openapi_doc)`
- `("GET", "/{path}", not_found)`

`GET /api/v2/users` (and its `dept` filter) now serves the user list, `GET /openapi.json`
serves the unchanged contract, and unknown paths such as `/api/v3/anything` still fall
through to the catch-all 404. The catch-all handler itself is unchanged; only the
registration order moved.

`python3 tests/api_test.py` passes: 7 checks (v2 users list, dept filter, catch-all 404s,
openapi served, health).
