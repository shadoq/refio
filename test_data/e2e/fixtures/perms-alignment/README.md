# Permission manifest - app host

- `etc/perms.csv` - the manifest the config-management pipeline deploys (drifted).
- `docs/perms-policy.md` - the authoritative permission policy.
- `var/lib/app/cache/`, `opt/app/bin/run.sh`, `var/log/app/app.log`, `etc/secrets.env`,
  `etc/app.conf` - the mirrored paths the policy covers.
- `checks.py` - the acceptance gate (do not modify it).
