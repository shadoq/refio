# tls-chain

Certificate bundle for the api.internal rollout review. `certs/` holds the
root, the issuing CA and the three server certificates the change request
asks about. `docs/runbook.md` describes the quarterly verification drill.

Run `python3 checks.py` from the project root after writing the report.
