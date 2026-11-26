# Emergency PKI migration report

The gateway moved off the 2019 SHA-1 CA: a new SHA-256 CA was built in
pki/current/, the server certificate was re-issued from the same CSR, the
legacy CA published a CRL revoking the old serial, and proxy/gw.conf now
references only the new files.

old_serial = 5A73E6C18D0B9F42
new_serial = 1C4B82F0A97D3E55
old_sig = sha1WithRSAEncryption
new_sig = sha256WithRSAEncryption
crl_entries = 1
trust_anchor = pki/current/ca.crt
proxy_cert = pki/current/server.crt
proxy_key = pki/server.key
