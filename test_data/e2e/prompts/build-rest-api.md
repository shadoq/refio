Build a small REST API in Python using ONLY the standard library (for example `http.server` /
`socketserver`) - no Flask, FastAPI, or any third-party package.

Put the whole server in `app.py`. Running `python3 app.py` must start it listening on
`127.0.0.1` port `8792` and keep running (serve forever) until the process is killed.

Endpoints (all responses JSON, with `Content-Type: application/json`):
- `GET /health` -> status 200, body `{"status": "ok"}`.
- `GET /todos` -> status 200, a JSON array of the todo items created so far (starts empty: `[]`).
- `POST /todos` with a JSON body `{"title": "<text>"}` -> status 201, creates a todo. After this,
  `GET /todos` must include an item whose title is exactly that text.

Keep the todo list in memory (a module-level list is fine). Use correct HTTP status codes.
Do not use any third-party libraries.
