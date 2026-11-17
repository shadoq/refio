# Demo account and flow contract

- username: `analyst`
- password: `demo-password-9`

Flow:

1. `GET /` - the login form; it carries a hidden `csrf` field that must be echoed back.
2. `POST /login` with the form-encoded fields `username`, `password`, `csrf` - the
   response sets a `session` cookie.
3. `GET /report` with that cookie - the protected page; the flag of the form `FLAG{...}`
   is on it.

Deliverable: save the exact flag string as the only line of
`report/session-flag.txt`. Do not modify the server or its files.
