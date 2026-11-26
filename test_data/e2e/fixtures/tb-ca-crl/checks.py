#!/usr/bin/env python3
"""Acceptance gate: re-derive the incident from docs/incident.md, ca/ and
certs/, then check crl/crl.pem and report/top-users.txt with openssl: the CRL
must parse, be signed by the root CA, revoke exactly the incident serials,
fail crl_check for the revoked certificates and pass it for the rest, and the
report must carry each revoked user with the first 16 hex chars of the SHA-256
of that certificate's DER encoding. Nothing is hard-coded."""
import hashlib
import os
import re
import subprocess
import sys

CA_CERT = "ca/ca.crt"
CRL_PATH = "crl/crl.pem"
REPORT = "report/top-users.txt"


def fail(msg):
    print("FAIL: " + msg)
    sys.exit(1)


def openssl(args, binary=False):
    return subprocess.run(["openssl"] + args, capture_output=True,
                          text=not binary)


def incident_users():
    with open("docs/incident.md", encoding="utf-8") as fh:
        text = fh.read()
    m = re.search(r"^## Users to revoke\s*$", text, re.M)
    if not m:
        fail("internal check - no 'Users to revoke' section (scenario drift)")
    section = text[m.end():]
    nxt = re.search(r"^## ", section, re.M)
    if nxt:
        section = section[:nxt.start()]
    users = re.findall(r"^-\s+([a-z0-9-]+)\s*$", section, re.M)
    if len(users) != 4:
        fail("internal check - expected 4 users to revoke (scenario drift)")
    return users


def main() -> int:
    users = incident_users()
    all_users = sorted(f[:-4] for f in os.listdir("certs")
                       if f.endswith(".pem"))
    if len(all_users) != 6 or not set(users) <= set(all_users):
        fail("internal check - certs/ does not match the incident (scenario drift)")

    # 1. the CRL exists, parses and is signed by the root CA
    r = openssl(["crl", "-in", CRL_PATH, "-noout"])
    if r.returncode != 0:
        fail("crl/crl.pem is missing or not parseable")
    r = openssl(["crl", "-in", CRL_PATH, "-CAfile", CA_CERT, "-noout"])
    if r.returncode != 0 or "verify OK" not in (r.stdout + r.stderr):
        fail("crl/crl.pem is not signed by the root CA in ca/")

    # 2. the CRL revokes exactly the incident certificates
    r = openssl(["crl", "-in", CRL_PATH, "-noout", "-text"])
    revoked = {int(s, 16) for s in
               re.findall(r"Serial Number:\s*([0-9A-Fa-f]+)", r.stdout)}
    serials = {}
    for u in all_users:
        rr = openssl(["x509", "-in", "certs/%s.pem" % u, "-noout", "-serial"])
        if rr.returncode != 0:
            fail("the certificate of %s does not parse" % u)
        serials[u] = int(rr.stdout.strip().split("=", 1)[1], 16)
    if revoked != {serials[u] for u in users}:
        fail("the CRL does not revoke exactly the users named in the incident")

    # 3. crl_check on every certificate: revoked fail, the rest pass
    for u in all_users:
        r = openssl(["verify", "-crl_check", "-CAfile", CA_CERT,
                     "-CRLfile", CRL_PATH, "certs/%s.pem" % u])
        if u in users and r.returncode == 0:
            fail("%s is named in the incident but still verifies" % u)
        if u not in users and r.returncode != 0:
            fail("%s is not part of the incident and must keep verifying" % u)

    # 4. the report: one 'username sha256prefix' line per revoked user
    try:
        with open(REPORT, encoding="utf-8") as fh:
            raw = fh.read()
    except OSError:
        fail("report/top-users.txt is missing")
    rows = [l for l in raw.splitlines() if l.strip()]
    expected = []
    for u in sorted(users):
        der = openssl(["x509", "-in", "certs/%s.pem" % u, "-outform", "DER"],
                      binary=True).stdout
        expected.append("%s %s" % (u, hashlib.sha256(der).hexdigest()[:16]))
    if len(rows) != len(expected):
        fail("report/top-users.txt must have one line per revoked user, "
             "sorted by username")
    for i, line in enumerate(rows):
        if not re.match(r"^[a-z0-9-]+ [0-9a-f]{16}$", line):
            fail("line %d of report/top-users.txt is not 'username "
                 "sha256prefix'" % (i + 1))
    if rows != expected:
        fail("report/top-users.txt does not match the certificates of the "
             "revoked users (check the hash is over the DER encoding)")
    print("PASS: the CRL and the report match the incident")
    return 0


if __name__ == "__main__":
    sys.exit(main())
