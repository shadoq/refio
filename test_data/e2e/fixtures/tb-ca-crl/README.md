# Northgate client PKI

- `ca/` - the Northgate Root CA (private key + certificate). The CA itself is
  not compromised and stays exactly as it is.
- `certs/` - the six client certificates issued by that CA.
- `docs/incident.md` - the incident report and the required end state
  (authoritative).
- `checks.py` - the acceptance gate (do not modify it); it shells out to
  openssl.

Work the incident: publish `crl/crl.pem` and `report/top-users.txt` per
`docs/incident.md`.
