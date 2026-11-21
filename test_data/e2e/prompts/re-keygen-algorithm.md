A licence-protected demo ships in this project:

- `license.py` - the checker. `validate_key(user, key)` accepts keys of the form
  `XXXX-YYYY` (four uppercase hex digits, a dash, four uppercase letters). The CLI
  (`python3 license.py <user> <key>`) prints `VALID` or `INVALID`.
- `verify_key.py` - the acceptance gate for this task (do not modify it; see README).
- `README.md` - product notes.

The vendor is gone; there is no keygen. Your task: reverse-engineer `validate_key` until you
can compute keys yourself, then generate a valid key for exactly the user `refio-user` and
write it as the only content of a new file `key.txt` in the project root (trailing newline is
fine).

Your key must pass `python3 verify_key.py` with exit code 0. Do not modify `license.py`,
`verify_key.py` or the README - a patched checker proves nothing.
