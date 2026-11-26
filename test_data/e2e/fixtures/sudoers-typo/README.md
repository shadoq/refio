# Host config snapshot: web03

Offline copy of the sudo policy area of host `web03`, pulled after the April
2026 access review flagged an anomaly. The deploy pipeline on this host uses a
dedicated `deploy` account that is only ever supposed to restart the in-house
`app` service; everything else goes through the operators.

- `etc/sudoers` - the long-standing main policy file, unchanged for years.
- `etc/sudoers.d/deploy` - the drop-in added by the deployment automation.
- `docs/intent.md` - the change ticket that requested the deploy rule. What is
  in production must match what the ticket asked for, exactly.
- `check.py` - acceptance gate for this review (run `python3 check.py`).

Write your triage to `report/triage.txt`. Nothing here is live; no answer
depends on timestamps or ownership.
