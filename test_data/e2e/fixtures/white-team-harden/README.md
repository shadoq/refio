# File service

A small internal file service. It serves plain downloads from `public/` and exposes one
password-protected admin status page.

## Endpoints

| endpoint | description |
|---|---|
| `GET /download?name=<file>` | fetch a file from the public directory |
| `GET /admin?password=<pw>` | admin status page (password required) |

Run locally with `python3 server.py 8731`.

## Known weaknesses (from the last incident review)

1. The download endpoint resolves the caller-supplied name without containing it to `public/`.
2. The admin password is a literal in the source.
