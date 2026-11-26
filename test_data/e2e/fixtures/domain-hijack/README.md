# ExampleGateway

The gateway of api.example.test: a front proxy and the real backend in one small
process, Python standard library only.

- `server/app.py` - the whole service. The gateway is authoritative for
  api.example.test only (every other Host gets a plain 404). The real backend lives
  behind the `/internal` prefix (`/internal`, `/internal/status`,
  `/internal/api/v1/health`) and sets the site's security headers
  (`X-Content-Type-Options`, `X-Frame-Options`, `Strict-Transport-Security`); the
  public routes (`/`, `/status`, `/api/v1/health`) are proxied onto the same backend
  pages.
- `docs/incident.md` - the hijack incident note.
- `tests/restore_test.py` - the live gate: it boots the gateway itself and checks the
  restored behaviour. Run it with `python3 tests/restore_test.py`.
- `report/` - incident write-ups live here.

History: the gateway's interception layer started answering for api.example.test
itself (see the incident note), and the proxy copy stopped setting the backend's
security headers.
