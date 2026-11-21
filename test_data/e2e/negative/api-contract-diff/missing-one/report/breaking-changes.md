# Breaking changes: Orders API v1 -> v2 (as seen by client/sdk.py)

## Breaking changes

### 1. `GET /users/{id}` - response field `email` removed (renamed to `contact`)

- SDK impact: `get_user` reads `user["email"]`; against v2 that is a `KeyError`.

### 2. `GET /items` - query parameter `limit` is now required

- SDK impact: `list_items` never sends `limit`, so v2 rejects every call with 400.

## Non-breaking changes

- `GET /metrics` is new in v2 - additive, nothing in the SDK calls it, so it is safe.
