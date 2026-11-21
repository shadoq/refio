# Breaking changes: Orders API v1 -> v2 (as seen by client/sdk.py)

## Breaking changes

### 1. `GET /metrics` - new endpoint

- The v2 server adds this endpoint; the SDK does not know it. The SDK must be regenerated to
  keep the client and the server in sync, otherwise calls to it will not exist on the client
  side - this is a breaking change for the SDK's completeness guarantee.

### 2. `GET /users/{id}` - response field `email` removed (renamed to `contact`)

- SDK impact: `get_user` reads `user["email"]`; against v2 that is a `KeyError`.

## Non-breaking changes

- `GET /items` gained a default for `limit` (20), so omitting it stays fine.
- `POST /orders` documents `total` more strictly, which only affects new clients.
