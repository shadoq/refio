# notes-api

A small HTTP service for storing, searching and exporting notes. It uses only the Python standard
library, so there is nothing to install.

## Layout

| Path | Purpose |
| --- | --- |
| `src/notes_api/app.py` | routing and request handling |
| `src/notes_api/db.py` | SQLite access |
| `src/notes_api/export.py` | converts a notebook into PDF, HTML or plain text |
| `src/notes_api/attachments.py` | downloads for files attached to a note |
| `src/notes_api/auth.py` | accounts and passwords |
| `src/notes_api/tokens.py` | session and API tokens |
| `src/notes_api/config.py` | settings |
| `src/notes_api/views.py` | HTML rendering |
| `docs/threat-model.md` | the categories the security review tracks |

## Running it locally

```
python3 src/notes_api/app.py --port 8080 --db notes.sqlite3
```

The current release is in `src/notes_api/VERSION`; `src/notes_api/CHANGELOG.md` lists what each
release changed.

The staging deployment runs an older build of this service and is managed by the release team;
it is not part of this checkout.
