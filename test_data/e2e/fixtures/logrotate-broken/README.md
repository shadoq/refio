# App host config mirror

- `etc/logrotate.conf` - drifted; still the pre-incident policy.
- `docs/logrotate-spec.md` - the tightened policy (authoritative).
- `var/log/app/app.log` - the live log.

`checks.py` is the acceptance gate for the repair.
