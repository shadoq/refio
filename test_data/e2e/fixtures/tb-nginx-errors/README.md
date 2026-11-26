# Edge SLO watch

- `logs/access.log` - one hour of nginx combined-format traffic (do not modify).
- `docs/slo.md` - the SLO definition and the report format (authoritative).
- `checks.py` - the acceptance gate (do not modify it).

Produce `report/error-budget.txt` per `docs/slo.md`.
