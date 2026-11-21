# App host service mirror

- `etc/app.service` - the systemd unit (drifted; last touched during the migration).
- `etc/app.env` - the environment file the unit loads.
- `opt/app/` - the service code and its token file.
- `docs/service-spec.md` - the authoritative definition.

Symptom: the service fails to start after reboots. `checks.py` is the acceptance gate.
