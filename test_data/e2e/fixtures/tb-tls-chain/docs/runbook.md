# Quarterly TLS verification drill

Verify every server certificate in `certs/` against `certs/root.pem` with
`certs/intermediate.pem` available as the issuing CA, for the hostname
`api.internal` (the name the rollout will serve), then fill
`report/tls-report.txt` with one block per certificate:

    cert: <file stem>
    result: OK or the exact openssl failure keyword
    san: comma-separated DNS names
    cn: the subject CN
    issuer: the issuer CN

The gate re-runs openssl itself, so the report has to match what the tools
actually say. Do not modify anything under `certs/`.
