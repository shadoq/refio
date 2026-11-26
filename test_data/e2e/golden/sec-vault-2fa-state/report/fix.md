# 2FA session state flaw in KeyVault: fix report

state_key_before = username

state_key_after = session token

sessions_invalidated = yes

## What was wrong

`server/app.py` kept the login state machine in `LOGIN_STATE`, a dictionary keyed by the
USERNAME, with one entry per user holding the current session token and a `verified`
flag. `POST /login` minted a fresh session token for that single entry but carried the
existing `verified` flag forward. Once a user had completed 2FA once, every later login
for that user inherited `verified = True`, so logging in a second time with just the
password produced a session that could read `GET /vault` immediately: the second factor
was never re-requested. This is a session fixation / reset flaw: the 2FA requirement is
reset by the very action (a new login) that should re-arm it.

## The fix

`LOGIN_STATE` is now keyed by the session TOKEN, one entry per login: each entry records
its own `user` and its own `verified` flag. `POST /login` always creates a new entry with
`verified = False` and deletes every previous entry of the same user, so:

- every fresh login requires `POST /verify` again, for every account;
- the previous (stale) session token is invalidated by the relogin and is refused with
  401;
- the happy path (login, verify, read `/vault`) is unchanged, and different users'
  sessions no longer share any state.

`python3 tests/test_2fa.py` passes: 14 checks (happy path, relogin resets 2FA and
invalidates the old session, per-user isolation including a second account, bad
credentials rejected).
