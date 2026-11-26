# Rekeying plan

Owner: platform security. Status: approved, not yet carried out.

`vault/legacy.py` is being replaced by a new module, `vault/crypto.py`. This page is the
interface contract the application and the offsite verifier rely on. The implementation must use
the Python standard library only (the production image has no third-party crypto package).

## Module interface (`vault/crypto.py`)

| name | contract |
| --- | --- |
| `encrypt_record(password, plaintext) -> bytes` | encrypt one record body for the owner of `password`; `plaintext` is bytes or str |
| `decrypt_record(password, blob) -> bytes` | reverse of `encrypt_record`; raises `IntegrityError` for anything it cannot authenticate and never returns data for it |
| `IntegrityError` | the exception class `decrypt_record` raises (tampered blob, wrong password, unknown or truncated format) |
| `hash_password(password) -> str` | one-line password record |
| `verify_password(stored, password) -> bool` | check a password against such a record |
| `reencrypt_all(vault_path, users_path, passwords) -> int` | migrate a legacy `vault.db` and `users.db` in place; `passwords` maps user name to current plaintext password; returns the number of records migrated |
| `derive_key(password, salt, rounds=...) -> bytes` | the KDF, shared by records and passwords |

## Required design

- Nonce: 16 fresh random bytes per message, drawn from `secrets`.
- Key: PBKDF2-HMAC-SHA256 with at least 200 000 rounds. The salt must not be one hard-coded
  constant shared by everybody (the nonce itself or a per-user salt both work).
- Keystream: an HMAC-SHA256 chain keyed by the derived key (for example one block per counter
  value), so it never repeats inside a message and differs between messages.
- Integrity: encrypt-then-MAC with HMAC-SHA256 over version, nonce and ciphertext, verified with
  `hmac.compare_digest`.
- Passwords: PBKDF2-HMAC-SHA256 with a fresh random salt per user, verified in constant shape.
- MD5 and the legacy functions may only be used to read old records during the migration.

## Wire format

Record bodies are stored base64 in the `body` field. The first byte of a decoded body is the
format version.

- `0x01`: legacy: `0x01 || ciphertext || MD5(ciphertext)`.
- `0x02`: target format: `0x02 || nonce (16 bytes) || ciphertext (same length as the plaintext) || tag (32 bytes)`.

A password record is one string: `pbkdf2_sha256$<rounds>$<salt hex>$<digest hex>`, where digest
is PBKDF2-HMAC-SHA256(password, salt, rounds) as 32 bytes, and `<rounds>`
is the number of rounds that were used (decimal, at least 200000).

The migration reads the pre-migration files with `vault/legacy.py` and writes the new formats.
`vault/legacy.py` itself stays in the tree for that reader.
