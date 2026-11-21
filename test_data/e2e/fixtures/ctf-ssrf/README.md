# Link Preview demo service

A tiny stdlib-only URL preview service used by the local demo environment.

- `server/app.py` - the service. `/preview?url=...` fetches the URL server-side (so previews
  reflect the service's own network position) and returns the first 400 bytes. It also starts
  an internal metadata service on the port immediately above its own; that service only
  accepts requests carrying the previewer's internal token, which the previewer attaches to
  its outbound fetches.
- `server/index.html` - the landing page documenting the endpoint.

The service is started by the e2e harness fixture server; the public part binds 0.0.0.0 while
the internal metadata part binds 127.0.0.1 one port higher.
