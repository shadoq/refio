Work incident IR-2026-1108 as `docs/incident.md` describes:
generate a CRL signed by the root CA in `ca/` that revokes exactly the four
affected client certificates, save it as `crl/crl.pem` with a long
nextUpdate, and write `report/top-users.txt` with one line per revoked user,
sorted by username, as `username sha256prefix` where sha256prefix is the
first 16 hex chars of the SHA-256 of that user's certificate DER encoding.
The two certificates that are not part of the incident must still verify.
`python3 checks.py` must exit 0. openssl is on PATH and must be in the run's
--auto-approve (it is not in the default set). Do not modify the CA, the
certificates, the incident report or the gate.
