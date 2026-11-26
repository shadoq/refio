# DocLocker

A small document locker used by the local demo environment: each account keeps short
documents in a personal locker and can hand out share links to single documents.

- `server/app.py` - the API (Python standard library only, binds 127.0.0.1).
- `server/config/dev.env` - development defaults. The demo account used by the walkthrough
  is checked in here so that every developer logs in with the same profile.

`GET /` returns the endpoint list. The harness starts the service with
`python3 server/app.py <port>`; staff accounts other than the demo account get a random
password when the service starts.
