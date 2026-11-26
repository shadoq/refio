# PKIGate

A small TLS gateway for the demo environment, Python standard library only (TLS 1.3 via
the `ssl` module).

- `server/app.py` - the service. It requires a client certificate issued by the CA in
  `pki/ca.crt`. `/admin` additionally checks the presented client certificate against
  `crl/crl.pem` when that file exists; until a CRL is published, `/admin` stays closed.
- `pki/` - the CA (`ca.crt`, `ca.key`), the gateway's own TLS certificate
  (`server.crt`, `server.key`, CN ca-mint-gw.example.test) and the already-issued client
  certificate for the admin-console audit (`audit.crt`, `audit.key`).
- `crl/` - where the CRL has to be published as `crl.pem`.
- `checks.py` - the offline gate: `python3 checks.py` (uses openssl).
- `docs/incident.md` - the incident note about the compromised audit certificate.
- `report/` - incident write-ups live here.

The CA database for `openssl ca` lives in `ca/` (`index.txt`, `crlnumber`); the config
`ca/ca.cnf` points `openssl ca` at `pki/ca.crt` and `pki/ca.key`.
