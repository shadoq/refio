# PKI incident cleanup

The compromised CN=audit certificate was revoked (crl/crl.pem lists its
serial) and the CRL was published. The existing audit credential remains
the only client certificate; no new certificate was minted.

revoked_serial = 1A2B3C4D5E6F7081
crl_path = crl/crl.pem
