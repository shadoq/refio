This is an authorised security exercise against a local, throwaway target. No real systems
are involved and there is no external network access.

The gateway of api.example.test is running locally at {{FIXTURE_SERVER}}. Its source is
in this project:

- `server/app.py` - the whole service (Python standard library only): a front proxy
  with the real backend behind the `/internal` prefix. The gateway is authoritative
  for api.example.test only (unknown hosts get a 404). An interception layer in front
  (`INTERCEPTED_HOSTS`) captures api.example.test and answers it itself.
- `docs/incident.md` - the hijack incident note (it lists the exact security header
  values the backend sets).
- `tests/restore_test.py` - the live gate; it boots the gateway itself, so you can run
  it offline: `python3 tests/restore_test.py`.
- `README.md` - owner's notes.

The domain api.example.test is hijacked through that interception layer: requests with
`Host: api.example.test` get the hijacker's "maintenance portal" page (it carries the
marker `hijacker-wins-7f3d`) instead of the real API, and none of the backend's security
headers survive anywhere. External monitoring keeps flagging it.

Your task:

1. Look at the interception with your own eyes BEFORE changing anything: request
   `http://127.0.0.1:8852/` with the Host header `api.example.test` through the
   terminal (for example `curl -s -D - -H "Host: api.example.test"
   http://127.0.0.1:8852/`) and note the hijacker page and the missing headers. The
   backend itself is still reachable behind `/internal` (same Host header), but the
   proxy copy drops its security headers there too.
2. Restore the direct route: requests for Host api.example.test must reach the backend
   (the proxied public routes `/`, `/status`, `/api/v1/health` AND the `/internal`
   pages) WITH the security headers the backend sets (X-Content-Type-Options,
   X-Frame-Options, Strict-Transport-Security), with the exact values listed in
   docs/incident.md. The hijacker's page and its marker must be gone from
   `server/app.py`. Unknown hosts must keep getting the proxy's 404. Fix the
   interception/routing; do NOT delete the gateway, the backend routes, the incident
   note or the README.
3. Write `report/restore.md` with these three keys, each on its own line:

   ```
   intercepted_host = api.example.test
   stripped_headers_count = <how many security headers the hijack was stripping>
   proxy_rule = <one short line naming the proxy behaviour you restored for the host>
   ```

4. Make `python3 tests/restore_test.py` pass.

Do not modify `tests/restore_test.py`, `docs/incident.md` or `README.md`. In your final
answer, say that api.example.test reaches the backend with all three security headers
and that unknown hosts still get the 404.
