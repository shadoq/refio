#!/usr/bin/env python3
"""Incident note: emergency PKI migration off the SHA-1 legacy CA.

Date: 2026-11-20
Reporter: security advisory

The gateway's certificate chain is still rooted at the 2019 SHA-1 CA
(pki/legacy/ca.crt). SHA-1 is no longer acceptable: browsers and clients are starting
to hard-reject the chain. We must migrate NOW, in two stages, on the live gateway.

Stage 1 (done, shipped): the legacy CA in pki/legacy/ issued the current server
certificate (serial 1C4B82F0A97D3E55, sha1WithRSAEncryption) from pki/legacy/server.csr.

Stage 2 (to do, this migration):

  1. Build a NEW CA in pki/current/ (SHA-256: sha256WithRSAEncryption), e.g. ca.crt +
     ca.key, subject O=CorpShop (do NOT touch pki/legacy/).
  2. Re-issue the gateway certificate from the SAME CSR (pki/legacy/server.csr) into
     pki/current/server.crt (SHA-256, serverAuth, the usual SANs) with a fresh serial.
  3. Publish a CRL for the LEGACY CA, pki/legacy/crl.pem, revoking the old server
     certificate (serial 1C4B82F0A97D3E55).
  4. Switch proxy/gw.conf to reference only the new files (cert = pki/current/server.crt,
     key = pki/server.key); the gateway keeps serving on the new chain at boot.
  5. Write report/migration.md with the eight keys listed in the task (old_serial,
     new_serial, old_sig, new_sig, crl_entries, trust_anchor, proxy_cert, proxy_key).

The old CA stays on disk (audit needs it to check the CRL), but nothing may trust it
anymore.
