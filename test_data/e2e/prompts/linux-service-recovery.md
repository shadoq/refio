The orders service on the app host fails to come up after a reboot and nobody wrote down
what changed. This project is the host's service mirror:

- `etc/app.service` - the systemd unit (drifted).
- `etc/app.env` - the environment file the unit loads.
- `opt/app/server.py` - the service itself (stdlib-only HTTP server; reads `APP_PORT` and
  `TOKEN_FILE` from the environment). Do not modify it.
- `opt/app/token.txt` - the service's token file.
- `docs/service-spec.md` - the authoritative service definition.
- `checks.py` - the acceptance gate (do not modify it).

Your task: repair `etc/app.service` and `etc/app.env` so the service definition matches
the spec **and** the service actually starts: `python3 checks.py` must exit 0 - it parses
the unit against the spec, then boots `opt/app/server.py` with the env file applied and
probes `/health` over HTTP until it answers. Do not modify the server, the token file, the
spec, the gate or the README.
