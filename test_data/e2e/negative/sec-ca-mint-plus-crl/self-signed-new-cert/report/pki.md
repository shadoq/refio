# PKI incident cleanup: mint and revoke

The compromised CN=audit client certificate was revoked and a fresh
CA-signed certificate was minted for the incoming auditor (CN=auditor).

new_cert_serial = 4C7A93E5B1D0F6A8
revoked_serial = 1A2B3C4D5E6F7081
crl_path = crl/crl.pem
