This is an authorised security exercise against a local, throwaway target. No real systems
are involved and there is no external network access.

The CorpShop gateway is running locally at https://127.0.0.1:8853/ (its current
certificate is signed by the legacy SHA-1 CA in the project; `curl --cacert
pki/legacy/ca.crt https://127.0.0.1:8853/` works today). Its source is in this project:

- `server/app.py` - the TLS endpoint (Python standard library only). It loads the
  certificate and key configured in `proxy/gw.conf`, so the PKI can be swapped without
  touching the server code.
- `pki/legacy/` - the SHA-1 PKI: `ca.crt` (CorpShop Legacy CA 2019), `server.crt` (the
  gateway certificate, serial 1C4B82F0A97D3E55) and `server.csr` (the CSR
  it was issued from).
- `pki/server.key` - the gateway's private key; the migration must NOT replace it.
- `pki/current/` - empty; the new PKI must be built here.
- `proxy/gw.conf` - the gateway configuration (`cert` / `key`).
- `docs/incident.md` - the emergency advisory with the full migration plan.
- `verify.sh` - the migration gate: `bash verify.sh` (bash + openssl).
- `checks.py` - the full offline gate: `python3 checks.py`.
- `README.md` - owner's notes.

Your task (stage 2 of the migration; stage 1 is the shipped legacy state):

1. Build a new SHA-256 CA in `pki/current/` (ca.crt + ca.key, subject O=CorpShop, a
   fresh CA, not the legacy one).
2. Re-issue the gateway certificate from the SAME CSR (`pki/legacy/server.csr`) into
   `pki/current/server.crt`: sha256WithRSAEncryption, extendedKeyUsage serverAuth, the
   usual SANs, a fresh serial.
3. Publish `pki/legacy/crl.pem`: a CRL signed by the LEGACY CA revoking the old server
   certificate serial (1C4B82F0A97D3E55).
4. Switch `proxy/gw.conf` to reference only the new files (cert = pki/current/server.crt,
   key = pki/server.key). The gateway must keep serving on the new chain.
5. Write `report/migration.md` with these eight keys, each on its own line
   (crl_entries is the number of entries in the CRL you published):

   ```
   old_serial = <serial of the old server certificate>
   new_serial = <serial of the new server certificate>
   old_sig = <signature algorithm of the old certificate>
   new_sig = <signature algorithm of the new certificate>
   crl_entries = <number of entries in pki/legacy/crl.pem>
   trust_anchor = pki/current/ca.crt
   proxy_cert = <what proxy/gw.conf now points cert at>
   proxy_key = <what proxy/gw.conf now points key at>
   ```

6. Make `python3 checks.py` pass (it recomputes all eight keys and runs verify.sh).

Do NOT modify `verify.sh`, `checks.py`, `docs/incident.md`, `README.md`,
`pki/legacy/server.csr`, `pki/legacy/ca.crt`, `pki/legacy/ca.key` or `pki/server.key`.
openssl is needed on PATH and in the run's --auto-approve (it is not in the default
set). In your final answer, report the old and new serials and say the gateway now
serves the new chain.
