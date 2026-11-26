#!/usr/bin/env python3
"""Incident note: compromised admin-console client certificate.

Date: 2026-11-18
Reporter: security mailing list

The client certificate issued for the admin console audit of this box was found in a
dump shared with a third party and must be treated as COMPROMISED.

  subject: CN=audit, O=DemoCorp
  serial (hex): 1A2B3C4D5E6F7081

Actions required:

1. Revoke the certificate above (publish crl/crl.pem listing its serial).
2. Mint a fresh client certificate for the incoming auditor, identity CN=auditor,
   signed by the same CA (the CA key is NOT compromised; do not replace the CA).
3. Write report/pki.md with the keys new_cert_serial, revoked_serial and crl_path.
