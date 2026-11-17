The nightly jobs on the app host have been misbehaving since a hasty edit session. This
project is the host's config mirror:

- `etc/crontab` - the crontab as it stands now (drifted).
- `bin/` - the scripts the jobs run.
- `docs/cron-spec.md` - the schedule policy the crontab must satisfy (authoritative).
- `var/log/app.log` - a sample of what the app logged.
- `checks.py` - the acceptance gate (do not modify it).

Your task: repair `etc/crontab` so every job matches the schedule policy in
`docs/cron-spec.md` exactly, and fix whatever makes the cleanup job silently do nothing
when cron runs it (look at how the file starts). `python3 checks.py` must exit 0 - it
parses the crontab with its own cron field parser and checks every schedule against the
spec, so approximate schedules fail. Do not modify the spec, the gate, the log or the
README.
