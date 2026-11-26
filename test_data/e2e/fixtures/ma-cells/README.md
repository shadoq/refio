# notes service - red cells / blue cell exercise

- `server/app.py` - the service (started by the harness on 127.0.0.1:8821).
- `.refio/agents/` - the cell profiles (web-cell, infra-cell, blue-cell).
- `attack/` - where the red cells write their findings.
- `report/` - where the blue cell documents its fixes.
- `attack_check.py` - the acceptance gate (run `python3 attack_check.py`).

This is an authorised exercise against a local, throwaway target. No real systems
are involved and there is no external network access.
