# TokenGate

A small internal gateway for the demo environment, Python standard library only.

- `server/app.py` - the service. Two of its secrets are embedded as constants
  (`SESSION_PEPPER`, `ADMIN_API_KEY`); the rest come from `config/seeds.json`, and the
  legacy backend token is stored in the `tokens` table of `db/seed.sqlite`.
- `config/seeds.json` - seed material loaded at boot (`API_TOKEN_SEED`, `WEBHOOK_HMAC_KEY`).
- `db/seed.sqlite` - the token store (table `tokens`, columns `name`, `value`).
- `tests/rotation_test.py` - the offline rotation suite: `python3 tests/rotation_test.py`.

All five secrets were generated in 2021 and have now been exposed through a leaked
backups archive, so they must be rotated: new values everywhere, the app still booting,
and the retired values gone from the tree.
