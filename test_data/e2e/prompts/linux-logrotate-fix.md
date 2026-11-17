The log policy for the app host has been tightened after a disk incident, but the host's
`logrotate.conf` was never brought in line. This project is the host's config mirror:

- `etc/logrotate.conf` - the current (drifted) policy.
- `docs/logrotate-spec.md` - the authoritative retention policy.
- `var/log/app/app.log` - the live log (leave it alone).
- `checks.py` - the acceptance gate (do not modify it).

Your task: edit `etc/logrotate.conf` so it implements the policy in
`docs/logrotate-spec.md` exactly - rotation frequency, retention count, compression,
creation mode/owner, the `su` directive, dateext, and the correct reload command in
`postrotate`. `python3 checks.py` must exit 0 (it validates the conf field by field
against the spec). Do not modify the spec, the gate, the log or the README.
