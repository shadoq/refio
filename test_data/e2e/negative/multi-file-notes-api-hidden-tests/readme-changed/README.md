# Notes API

An HTTP API for notes, kept in memory. No database, no dependencies: Node's own `http` module only.

The server starts with `node src/server.js` and listens on the port in the `PORT` environment variable
(default 3000). It prints nothing that the tests depend on.

All request and response bodies are JSON. A response body is `{}` only where stated.

## Endpoints

### GET /health
200, body `{"status":"ok"}`. Must answer as soon as the server is listening.

### POST /notes
Body: `{"title": string, "body": string, "tags": string[]}`. `body` and `tags` are optional and default
to `""` and `[]`.

201 with the created note: `{"id", "title", "body", "tags", "createdAt", "updatedAt"}`. `id` is a string
unique per note. `createdAt` and `updatedAt` are ISO 8601 strings. The response carries an `ETag` header
for that version of the note.

Validation: a missing or empty `title`, a `title` longer than 120 characters, a `tags` value that is not
an array of strings, or a `body` that is not a string is 400 with
`{"error":{"code":"validation","fields":[...]}}`, where `fields` names the offending fields.

### GET /notes
Query parameters: `tag` (keep only notes carrying that tag), `page` (1-based, default 1), `limit`
(default 10, maximum 100).

200 with `{"items": [...], "page": n, "limit": n, "total": n}`. `total` counts the notes matching the
filter, not the page. Items are sorted by `createdAt` descending, newest first.

### GET /notes/:id
200 with the note and its `ETag` header. A request carrying `If-None-Match` equal to the current ETag is
304 with an empty body. An unknown id is 404 with `{"error":{"code":"not_found"}}`.

### PUT /notes/:id
Body: the same shape as POST; the same validation applies. 200 with the updated note, a refreshed
`updatedAt` and a new `ETag`. `createdAt` never changes.

A request carrying `If-Match` that does not equal the current ETag is 412 with
`{"error":{"code":"conflict"}}` and changes nothing. Without `If-Match` the update proceeds.

An unknown id is 404.

### DELETE /notes/:id
204 with an empty body. Fetching or deleting the same id afterwards is 404.

## Errors

An unknown path or method is 404 with `{"error":{"code":"not_found"}}`.
A body that is not valid JSON is 400 with `{"error":{"code":"invalid_json"}}`.

Validation is optional.
