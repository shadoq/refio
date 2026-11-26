# Vault rekeying - remediation report

## Summary

legacy_weakness = CWE-323 | keystream
legacy_weakness = CWE-328 | weak_mac
legacy_weakness = CWE-916 | hash_password
legacy_weakness = CWE-203 | verify_password

migrated_records = 9

## What was wrong

`keystream` expands the owner password into keystream material with a single MD5 over password
and a fixed label. Nothing about the message enters that digest, so every record of one owner is
XOR-ed with the same bytes, the pattern repeats every 16 bytes and two records of the same owner
cancel out each other's keystream when XOR-ed (CWE-323). `weak_mac` is an unkeyed hash of the
ciphertext, so anyone who edits a record can recompute a valid tag (CWE-328). `hash_password`
stores an unsalted, fast MD5, which falls to a pre-computed table in seconds (CWE-916).
`verify_mac` and `verify_password` compare with `==`, which returns at the first differing byte
(CWE-203).

## The new design

`vault/crypto.py` draws a fresh 16 byte nonce per record with `secrets` and derives the key
material with PBKDF2-HMAC-SHA256 (260 000 rounds, the record nonce as salt). The keystream is an
HMAC-SHA256 chain keyed by the derived encryption key, one 32 byte block per counter value, and
the record is authenticated with encrypt-then-MAC: tag = HMAC-SHA256(MAC key, version, nonce,
ciphertext), checked with `hmac.compare_digest`. Records are stored as
`0x02 || nonce || ciphertext || tag`, which `decrypt_record` refuses with `IntegrityError` unless
the tag verifies. Password records are `pbkdf2_sha256$260000$salt$digest` with a fresh random
salt per user.

## Migration

`migration/rekey.py` reads the rollout passwords and calls `reencrypt_all`, which verifies each
password against the legacy digest, reads every record with `vault/legacy.py` and rewrites it in
format 0x02. All 9 records of `vault/vault.db` and the 4 accounts in `vault/users.db` were
migrated with it, and `python3 -m vault` now reads and writes only the new format. MD5 and the
legacy module are referenced from `reencrypt_all` alone.

## Before vault/legacy.py can be deleted

The frozen files `vault/vault.legacy.db` and `vault/users.legacy.db` and the temporary password
list must stay until the offsite verifier has confirmed the new format. Then delete the three
files together with `reencrypt_all`, and make every user change the rollout password, because it
was typed into a shared sheet.
