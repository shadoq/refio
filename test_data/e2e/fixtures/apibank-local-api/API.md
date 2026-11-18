# Items service API

Base URL is configured by the caller. All requests send `Authorization: Bearer <token>`. Bodies are JSON.

## Endpoints

- `GET /v1/items?limit=<n>&cursor=<c>` returns `{ "items": [...], "next_cursor": "<c>" | null }`. Omit `cursor` for the first page. The cursor is opaque and may contain any character (`+`, `/`, `=`, spaces), so it must be URL-encoded when sent back. `next_cursor` is `null` on the last page.
- `GET /v1/items/<id>` returns the item. Unknown ids answer 404.
- `POST /v1/items` with a JSON body creates an item and answers 201 with the created item.

Errors carry a JSON body `{ "error": "<message>" }`.

## Rate limiting (429)

The service answers 429 when a client is too fast. If the response has a `Retry-After` header (whole seconds) wait exactly that long, otherwise wait 1 second, then 2, then 4, doubling each time. Every method retries on 429, at most 5 attempts in total. After the fifth 429 give up.

## Server errors (5xx)

Only idempotent reads (`GET`) are retried on a 5xx: at most 2 retries (3 attempts in total), waiting 100 ms before the first retry and 200 ms before the second. A `POST` is never retried on a 5xx, because the item may already have been created.

## Other errors

Any other 4xx is final: no retry. Give up at once.

## Client contract

`client.js` exports `ApiClient` and `ApiError`.

- `new ApiClient({ baseUrl, token, sleep })`. `sleep(ms)` returns a promise and defaults to a real timer; tests inject a fake one.
- `listAll({ pageSize = 50 })` follows the cursors and resolves to the concatenated array of items.
- `getItem(id)` resolves to the item, or to `null` on a 404.
- `createItem(body)` resolves to the created item.
- Failures reject with `ApiError` having `status` (HTTP status) and `message` (the `error` field of the body, or `HTTP <status>` when there is none).
