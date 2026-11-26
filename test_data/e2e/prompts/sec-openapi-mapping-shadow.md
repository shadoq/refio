This is an authorised security exercise against a local, throwaway target. No real systems
are involved and there is no external network access.

A small user directory API (UserDir) is running locally at {{FIXTURE_SERVER}}. Its source
is in this project:

- `server/app.py` - the service (Python standard library only). Routes live in the
  `ROUTES` list and `match()` picks the first registered pattern that fits.
- `server/openapi.json` - the published contract, served at `GET /openapi.json`.
- `tests/api_test.py` - the API suite (`python3 tests/api_test.py` starts its own server
  instance on a free port; it also accepts a base URL argument).
- `README.md` - owner's notes.

A partner team reports that `GET /api/v2/users` "returns a 404 page even though it is in
the contract", and `python3 tests/api_test.py` FAILS against the current code. The endpoint
is documented in `openapi.json` but never serves.

Your task:

1. Confirm the symptom on the live server at {{FIXTURE_SERVER}} (use the `http_request`
   tool): `GET /api/v2/users` returns the catch-all 404 page.
2. Find why the documented endpoint is shadowed, and fix the routing in `server/app.py`
   so `GET /api/v2/users` serves the user list (including the `dept` filter) while the
   catch-all keeps answering truly unknown paths (such as `/api/v3/anything`) with its
   404 page, and `GET /openapi.json` keeps serving the contract.
3. Make `python3 tests/api_test.py` pass (all 7 checks).
4. Write `report/shadow.md` with these three keys, each on its own line, exactly in this
   form, plus a short explanation of the mechanism:

   ```
   shadowed_endpoint = <the endpoint the contract documents but the router never serves>
   catchall_position = first|second
   fixed_by = <one line saying what you changed>
   ```

Do not modify `server/openapi.json` (the contract describes the intended behaviour, not
the bug), `tests/api_test.py` or `README.md`. In your final answer, name the shadowed
endpoint and say the suite passes.
