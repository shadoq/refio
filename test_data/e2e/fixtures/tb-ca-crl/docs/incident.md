# Incident IR-2026-1108: leaked client credentials

During the night of 2026-11-08 the SOC found a dump of client credential
material on a paste site. Four of the six client certificates issued by the
Northgate Root CA are affected. The certificate authority itself is not
compromised: the CA key stays in place and the response is revocation only.

## Users to revoke

- alice
- carol
- erin
- frank

## Users that stay valid

bob and dave were checked and are not part of the dump; their certificates
must keep verifying after the new CRL is published. Do not revoke them.

## Required end state

Publish `crl/crl.pem`, a CRL signed by the root CA in `ca/`, that revokes
exactly the four certificates above. Give the CRL a long nextUpdate: the
harness may run at any later date, and the CRL must still be inside its
validity window then.

Write `report/top-users.txt` with one line per revoked user, sorted by
username, in the form:

    username sha256prefix

`sha256prefix` is the first 16 hex characters of the SHA-256 digest computed
over the certificate's DER encoding (convert with `openssl x509 -outform
DER`), not over the PEM text.

`python3 checks.py` must exit 0: it re-derives everything from these files
with openssl. Do not modify the CA, the certificates, this incident report or
the gate.
