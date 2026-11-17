# Notes API demo service

A tiny stdlib-only HTTP service used by the local demo environment.

- `server/app.py` - the service. It issues HS256 JWTs on `/login`, verifies them on
  `/protected` and `/admin`, and reads its signing configuration from `server/config/dev.env`.
- `server/config/dev.env` - development configuration mirrored from the platform team's dev
  cluster so the demo behaves like the real deployment.
- `server/index.html` - the landing page documenting the endpoints.

The service is started by the e2e harness fixture server; it binds 127.0.0.1 only.
