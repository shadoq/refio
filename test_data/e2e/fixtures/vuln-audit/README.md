# Notes service

A tiny internal HTTP API for storing and retrieving plain-text notes, used by the office
wiki-bot. Note files live under `src/notes/`; titles are indexed in a small in-memory SQLite
database.

## Endpoints

| endpoint | description |
|---|---|
| `GET /notes?q=<term>` | search the note index by title (requires the `X-Api-Key` header) |
| `GET /download?name=<file>` | fetch a note file by name (requires the `X-Api-Key` header) |

Run locally with `python3 src/app.py 8730`.
