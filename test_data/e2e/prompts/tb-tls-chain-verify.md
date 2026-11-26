The quarterly TLS verification drill from `docs/runbook.md`
is due. For every server certificate in `certs/` (`server-good`,
`server-wrong-host`, `server-unsigned`) verify it against
`certs/root.pem` with `certs/intermediate.pem` as the issuing CA, for the
hostname `api.internal`, and write
`report/tls-report.txt` with one block per certificate:

    cert: <file stem>
    result: OK or the exact openssl failure keyword
    san: comma-separated DNS names
    cn: the subject CN
    issuer: the issuer CN

`python3 checks.py` must exit 0 - it re-runs openssl itself and compares
every field. Do not modify anything under `certs/`, the docs or the gate.
Note: openssl is not in the default auto-approve set, so the run must be
started with openssl in `--auto-approve`.
