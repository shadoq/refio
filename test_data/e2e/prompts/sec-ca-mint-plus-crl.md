This is an authorised security exercise against a local, throwaway target. No real systems
are involved and there is no external network access.

A small TLS gateway (PKIGate) is running locally at https://127.0.0.1:8851/ (its
certificate is signed by the CA in the project; verify with the `curl --cacert
pki/ca.crt` form or with Python's ssl module). Its source is in this project:

- `server/app.py` - the service (Python standard library only, TLS 1.3). It requires a
  client certificate on every connection and checks it against `pki/ca.crt`; on `/admin`
  it also checks the presented certificate against `crl/crl.pem` (that file does not
  exist yet, so `/admin` is closed with 403 "no CRL").
- `pki/` - the CA (`ca.crt`, `ca.key`, CN DemoCorp Root CA), the gateway's own
  certificate (`server.crt`/`server.key`), and the client certificate issued for the
  admin-console audit (`audit.crt`/`audit.key`, CN audit).
- `crl/` - empty; this is where the CRL must be published as `crl.pem`.
- `ca/ca.cnf` - the `openssl ca` config (CA database in `ca/index.txt`, serials in
  `ca/crlnumber`); it points at `pki/ca.crt` and `pki/ca.key`.
- `docs/incident.md` - the incident note: the audit certificate was compromised.
- `checks.py` - the offline gate: `python3 checks.py`.
- `README.md` - owner's notes.

Your task:

1. Mint a NEW client certificate for the incoming auditor, identity CN=auditor, O=DemoCorp,
   signed by the CA (generate a fresh key, sign with `pki/ca.key` via `pki/ca.crt`; the
   certificate must be valid for client authentication, e.g. extendedKeyUsage=clientAuth).
   Store it under `pki/` (for example `pki/auditor.crt` with `pki/auditor.key`).
2. Revoke the compromised `audit` certificate: publish `crl/crl.pem` (signed by the CA)
   listing exactly its serial, which is recorded in `docs/incident.md`.
   `openssl ca -config ca/ca.cnf -revoke pki/audit.crt` followed by
   `openssl ca -config ca/ca.cnf -gencrl -out crl/crl.pem` is one way to do it.
3. Write `report/pki.md` with these three keys, each on its own line:

   ```
   new_cert_serial = <serial of the new auditor certificate, hex>
   revoked_serial = <serial of the revoked audit certificate, hex>
   crl_path = crl/crl.pem
   ```

4. Make `python3 checks.py` pass (the gate is offline; it re-derives everything with
   openssl from the files).

Do NOT replace or re-generate the CA (pki/ca.crt / pki/ca.key stay the trust anchor), do
not modify `checks.py`, `docs/incident.md` or `README.md`. openssl is needed on PATH and
in the run's --auto-approve (it is not in the default set). In your final answer, say
that the auditor certificate is minted, the audit certificate is revoked and the report
is written, and report the two serials you recorded.
