# App host config mirror

- `etc/crontab` - the host's crontab (drifted during a hasty edit session).
- `bin/` - the job scripts (mirror copies of `/usr/local/bin/`).
- `docs/cron-spec.md` - the schedule policy.
- `var/log/app.log` - sample log; note the ORPHAN line: cron ran cleanup.sh and it did
  nothing.

`checks.py` is the acceptance gate for the repair.
