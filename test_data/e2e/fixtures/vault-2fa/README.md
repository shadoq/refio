# KeyVault

A small vault login flow for the internal demo environment, Python standard library only.

- `server/app.py` - the service. The login is a two-step state machine:
  `POST /login {"user","password"}` starts a pending session and returns its token,
  `POST /verify {"session","token"}` completes it with a TOTP code, and only verified
  sessions may read `GET /vault` (send the session token as a Bearer token).
- `tests/test_2fa.py` - the suite. Run it with no arguments (it starts its own server on
  a free port) or against a live instance: `python3 tests/test_2fa.py http://127.0.0.1:8848`.

The login state lives in the `LOGIN_STATE` dictionary in `server/app.py`. Security review
left a note that the 2FA step "can be skipped by logging in twice"; the suite encodes the
expected behaviour.
