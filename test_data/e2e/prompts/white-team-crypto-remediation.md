You are the white team for this small document vault: replace its home-grown legacy crypto with
a sound design, migrate the data and keep the application working.

**What is wrong today.** `vault/legacy.py` is the code in production. It is a stream cipher whose
keystream is `MD5(password + fixed label)` repeated, so the same plaintext under the same password
always encrypts to the same ciphertext and the keystream repeats every 16 bytes. Its integrity
tag is `MD5(ciphertext)`, which anyone can recompute after changing the ciphertext. User passwords
are stored as unsalted MD5, and secrets are compared with a plain `==`. `migration/PLAN.md` holds
the approved design, the module interface and the wire format.

**What to deliver**

1. `vault/crypto.py` (Python standard library only) implementing the plan: a fresh 16 byte nonce
   per message drawn from `secrets`; a key derived with PBKDF2-HMAC-SHA256 (at least 200 000
   rounds, and no salt that is one hard-coded constant for everybody); an HMAC-SHA256 keystream
   chain; encrypt-then-MAC with HMAC-SHA256 verified through `hmac.compare_digest`; salted PBKDF2
   password records compared in constant shape; and `reencrypt_all`, the migration. MD5 and the
   legacy functions may only be used to read old records during the migration.
2. `vault/cli.py` pointed at the new module (same commands, same environment variables).
3. The migration carried out: every record in `vault/vault.db` in format `0x02` and every user
   in `vault/users.db` with a new password record. The passwords collected for the rollout are in
   `migration/rollout.json`.
4. `report/remediation.md` with a plain `key = value` block (one entry per line, no table):
   - four lines `legacy_weakness = CWE-<id> | <function name>`, one for each of CWE-323,
     CWE-328, CWE-916 and CWE-203, naming the function in `vault/legacy.py` that carries that
     weakness (nothing after the function name on the line);
   - one line `migrated_records = <n>` with the number of records the migration rewrote.

   Below the block, explain the new design briefly and what has to happen before the legacy
   module can be deleted.

**Acceptance.** `check.py` is the gate. It imports your module and proves by experiment that the
attacks are closed (keystream reuse, forgery, tampering, wrong password) and that legitimate use
still works (round trip, password verification, `reencrypt_all` on the legacy snapshot, the CLI on
the migrated files); it re-derives the report values from `vault/vault.legacy.db`. Run it with
`python3 check.py` and iterate until every check passes.

Do not modify `check.py`, `README.md`, `migration/PLAN.md`, `migration/rollout.json`,
`vault/legacy.py`, `vault/__main__.py`, `vault/vault.legacy.db` or `vault/users.legacy.db` - the
gate needs them as they are.
