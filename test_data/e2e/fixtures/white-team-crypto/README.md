# Refio document vault

A small internal document vault used by the support team to keep customer letters, handover
notes and call logs. Records live in `vault/vault.db`, users in `vault/users.db`; both are plain
JSON so the offsite backup job can read them.

## Layout

| path | purpose |
| --- | --- |
| `vault/legacy.py` | the crypto code that has been in production since 2019 |
| `vault/cli.py` | command line tool (`add`, `get`, `list`), started through `vault/__main__.py` |
| `vault/vault.db`, `vault/users.db` | the live data files |
| `vault/vault.legacy.db`, `vault/users.legacy.db` | frozen pre-migration copies written by the backup job; the rekeying is verified against them, do not edit |
| `migration/PLAN.md` | the approved rekeying plan and the interface contract of the new module |
| `migration/rollout.json` | temporary passwords the service desk collected for the rekeying rollout |
| `check.py` | acceptance gate for the rekeying work |

## Using the CLI

The caller's password is read from the `VAULT_PASSWORD` environment variable and the user is
named on the command line. Data files are resolved from the working directory; `VAULT_DB` and
`VAULT_USERS` override the paths.

```sh
export VAULT_PASSWORD=...
python3 -m vault add rec-101 alice "Handover note" < note.txt
python3 -m vault get rec-101 alice
python3 -m vault list alice
```

## Storage format

Record bodies are stored base64 in the `body` field. The first byte of a decoded body is the
format version: `0x01` is the legacy stream cipher, `0x02` is the target format of the rekeying
(`migration/PLAN.md` has the details). A user's `password_hash` field holds the password record.

## Known weaknesses

The legacy module was written under time pressure: the keystream does not depend on a nonce, the
integrity tag is a plain hash of the ciphertext, passwords are stored as unsalted MD5 and every
secret comparison is a plain `==`. It is being replaced; see `migration/PLAN.md`.
