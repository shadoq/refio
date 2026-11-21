# Stockroom API

A tiny in-memory stock service. `src/server.js` must export `createServer()`, which returns a Node `http.Server` that is NOT listening yet (the caller decides the port). Every call to `createServer()` returns an independent server with its own empty store. Use only Node built-ins.

All request and response bodies are JSON (`Content-Type: application/json`). All errors are `{"error": "<code>"}` with the status listed below.

## Item

```
{ "sku": "ABC-123", "qty": 5, "version": 1 }
```

`sku` matches `^[A-Z]{3}-[0-9]{3}$`. `qty` is a non-negative integer. `version` starts at 1 and grows by 1 on every successful change of the item.

## Endpoints

### POST /items
Body `{ "sku": string, "qty": integer }`.
- 201 with the item and a `Location: /items/<sku>` header.
- 400 `invalid_json` when the body is not parseable JSON or not an object.
- 400 `invalid_item` when `sku` or `qty` breaks the rules above.
- 409 `duplicate` when the sku already exists.

### GET /items/:sku
- 200 with the item and the header `ETag: "v<version>"` (the quotes are part of the value).
- 404 `not_found` for an unknown sku.

### PATCH /items/:sku
Body `{ "delta": integer }` (may be negative). The request must carry an `If-Match` header.
- 404 `not_found` for an unknown sku (checked first).
- 428 `precondition_required` when `If-Match` is missing.
- 412 `precondition_failed` when `If-Match` is not exactly the current ETag.
- 400 `invalid_json` / `invalid_delta` for a bad body (checked after the precondition).
- 422 `insufficient_stock` when `qty + delta` would be negative; the item is left untouched.
- 200 with the updated item and the new `ETag` header otherwise.

### DELETE /items/:sku
- 204 with no body, or 404 `not_found`.

### GET /items
Query parameters, all optional:
- `min_qty` - only items with `qty >= min_qty` (non-negative integer).
- `limit` - page size, integer 1..50, default 10.
- `cursor` - a sku; return only items whose sku sorts strictly after it.

Items are sorted by sku ascending (plain string comparison). Response `{ "items": [...], "next_cursor": <sku or null> }`. `next_cursor` is the sku of the last returned item when more matching items remain after this page, otherwise `null`.
- 400 `invalid_query` for a malformed `min_qty` or `limit`, or a `limit` outside 1..50.

## Routing
- Unknown path: 404 `not_found`.
- Known path with an unsupported method: 405 `method_not_allowed` with an `Allow` header listing the supported methods in exactly this order: `GET, POST` for `/items`, `GET, PATCH, DELETE` for `/items/:sku`.
