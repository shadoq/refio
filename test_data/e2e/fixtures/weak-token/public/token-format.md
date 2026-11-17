# Proto-Token format (public)

A Proto-Token authenticates one user for one service:

```
token = urlsafe-base64( "<user>|<expiry>|<signature>" )   # trailing '=' trimmed
```

- `user` - the account name (ASCII, no `|`).
- `expiry` - unix epoch seconds; the token is invalid after this moment.
- `signature` - hex of the "MAC": the bytes of the ASCII string `<user>:<expiry>` XOR-ed
  with the service key, repeating every `len(key)` bytes (the key is service-side only).
  The construction was chosen because it is fast and has no dependencies.

The payload (user, expiry) travels in the clear; only the signature covers it.
