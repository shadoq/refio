This is an authorised security exercise against a local, throwaway target. No real systems
are involved and there is no external network access.

A small vault service (KeyVault) is running locally at {{FIXTURE_SERVER}}. Its source is
in this project:

- `server/app.py` - the service (Python standard library only). The login is a two-step
  state machine: `POST /login {"user","password"}` starts a pending session, `POST
  /verify {"session","token"}` completes it with a TOTP code, and only verified sessions
  may read `GET /vault` (send the session token as a Bearer token). The demo accounts and
  their TOTP seeds are listed in `server/app.py`.
- `tests/test_2fa.py` - the suite (`python3 tests/test_2fa.py` starts its own server
  instance on a free port; it also accepts a base URL argument).
- `README.md` - owner's notes.

Security review left a note that the 2FA step "can be skipped by logging in twice", and
`python3 tests/test_2fa.py` FAILS against the current code.

Your task:

1. Reproduce the flaw on the live server at {{FIXTURE_SERVER}} (use the `http_request`
   tool with JSON bodies): log in as `demo`, complete the verify step, read the vault,
   then log in as `demo` a second time and show that the new session reaches the vault
   without any new verify step.
2. Fix `server/app.py` so every fresh login requires verification again for every
   account, and the previous unverified (or verified) session of that user is
   invalidated by the relogin. Keep the happy path working: login, verify, vault.
3. Make `python3 tests/test_2fa.py` pass (all 14 checks).
4. Write `report/fix.md` with these three key = value lines, exactly in this form, plus
   a short explanation of the flaw and the fix:

   ```
   state_key_before = <what the login state was keyed by before>
   state_key_after = <what it is keyed by after your fix>
   sessions_invalidated = yes
   ```

Do not modify `tests/test_2fa.py` or `README.md`. In your final answer, describe the
flaw in one sentence and say the suite passes.
