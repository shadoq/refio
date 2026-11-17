# Breaking changes: Orders API v1 -> v2 (as seen by client/sdk.py)

Three changes in v2 break the shipped v1 SDK. One addition is safe.

## Breaking changes

### 1. `GET /users/{id}` - response field `email` removed (renamed to `contact`)

- Change: the 200 response schema in v2 has `contact` where v1 had `email`.
- SDK impact: `get_user` in `client/sdk.py` reads `user["email"]` (the line commented
  `# billing notifications go here`). Against v2 that key is missing, so every call raises
  `KeyError` - user profiles are entirely broken, not just degraded.
- Fix: regenerate the SDK; meanwhile `get_user` must read `user["contact"]`.

### 2. `GET /items` - query parameter `limit` is now required

- Change: v1 marked `limit` as `required: false` (default 20); v2 marks it `required: true`.
- SDK impact: `list_items` in `client/sdk.py` never sends `limit` (it only sends `cursor`
  when paging). Against v2 every first-page call is a 400 - item listing is broken from the
  first request.
- Fix: `list_items` must send an explicit `limit` on every call.

### 3. `POST /orders` - request field `total` type changed from `number` to `string`

- Change: v1's request schema declares `total` as `number`; v2 declares it as `string`.
- SDK impact: `create_order` in `client/sdk.py` passes the caller's numeric total straight
  through (its docstring even promises "total as a number, e.g. 49.5"). A v2 server
  validating the request body rejects the numeric value with a 400, so order creation fails.
- Fix: convert to the string form the server now expects (e.g. `f"{total:.2f}"`).

## Non-breaking changes

- `GET /metrics` is **new in v2**: an additive endpoint nothing in the SDK calls. Existing
  clients are unaffected - it is additive, not breaking. (Worth adopting later, but it
  cannot break the v1 SDK.)

Everything else (paths, the `cursor` parameter, `/orders` required fields, response codes)
is unchanged between the two specs.
