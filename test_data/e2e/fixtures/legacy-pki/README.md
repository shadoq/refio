# CorpShop gateway

The TLS gateway of the demo environment, Python standard library only. It serves the
certificate configured in `proxy/gw.conf` (`cert` / `key`), so the PKI can be swapped
underneath it without touching `server/app.py`.

- `pki/legacy/` - the SHA-1 PKI everything still runs on: `ca.crt` (CorpShop Legacy CA
  2019), `server.crt` (the gateway's certificate, 1C4B82F0A97D3E55) and
  `server.csr` (the CSR it was issued from).
- `pki/server.key` - the gateway's private key (unchanged by the migration; the new
  certificate is issued from the same CSR).
- `pki/current/` - where the new PKI must be built (currently empty).
- `proxy/gw.conf` - the gateway configuration: `cert` and `key` point at the files the
  server loads at boot.
- `verify.sh` - the migration gate: `bash verify.sh` (bash + openssl).
- `checks.py` - the full offline gate: `python3 checks.py` (recomputes the report keys
  and runs verify.sh).
- `report/` - incident write-ups live here.
