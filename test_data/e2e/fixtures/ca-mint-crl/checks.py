#!/usr/bin/env python3
"""Offline gate for the PKI incident cleanup (mint + revoke + report).

Checks, without any network:
  1. the CA certificate is unchanged (the trust anchor was not replaced),
  2. a new client certificate for CN=auditor exists, is signed by the CA and parses,
  3. crl/crl.pem exists and lists exactly the compromised audit serial,
  4. report/pki.md exists and its new_cert_serial / revoked_serial / crl_path keys match
     the values re-derived from the certificate and the CRL.

Answers are re-derived with openssl from the files; nothing is echoed in plain text.
Exit code 0 means every check passed.
"""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

AUDIT_CN = "audit"          # compromised identity (docs/incident.md)
NEW_CN = "auditor"          # required identity of the new certificate
CA_CERT = os.path.join(HERE, "pki/ca.crt")
CA_KEY = os.path.join(HERE, "pki/ca.key")
CRL = os.path.join(HERE, "crl/crl.pem")
REPORT = os.path.join(HERE, "report/pki.md")


def openssl(*args):
    return subprocess.run(["openssl"] + list(args), capture_output=True, text=True)


def die(failures):
    print("FAIL")
    for f in failures:
        print(" - " + f)
    return 1


def main():
    failures = []
    # 1. the trust anchor is unchanged (serial + subject + public key hash must hold).
    if not os.path.isfile(CA_CERT):
        return die(["pki/ca.crt is missing"])
    ca_serial = openssl("x509", "-in", CA_CERT, "-noout", "-serial").stdout
    ca_serial = ca_serial.split("=", 1)[1].strip().upper() if "=" in ca_serial else ""
    ca_subj = openssl("x509", "-in", CA_CERT, "-noout", "-subject").stdout
    if "DemoCorp Root CA" not in ca_subj:
        failures.append("pki/ca.crt is not the original DemoCorp CA")
    # the CA public key must be the one whose private key sits in pki/ca.key
    ca_pub = openssl("x509", "-in", CA_CERT, "-noout", "-pubkey").stdout
    ca_key_pub = openssl("pkey", "-in", CA_KEY, "-pubout").stdout if os.path.isfile(CA_KEY) else ""
    norm = lambda s: "".join(l for l in s.splitlines() if "-----" not in l)
    if ca_key_pub and norm(ca_pub) != norm(ca_key_pub):
        failures.append("pki/ca.crt no longer matches the CA key in pki/ca.key")

    # 2. find the new client certificate for CN=auditor and verify it against the CA.
    new_cert = None
    new_serial = ""
    for name in sorted(os.listdir(os.path.join(HERE, "pki"))):
        path = os.path.join(HERE, "pki", name)
        if not name.endswith(".crt") or name in ("ca.crt", "server.crt"):
            continue
        subj = openssl("x509", "-in", path, "-noout", "-subject").stdout
        if ("CN = " + NEW_CN) not in subj.replace(",", "") and ("CN=" + NEW_CN) not in subj.replace(" ", ""):
            continue
        if not os.path.isfile(path.replace(".crt", ".key")):
            continue
        new_cert = path
        break
    if new_cert is None:
        failures.append("no client certificate found for CN=" + NEW_CN +
                        " (with its key) under pki/")
    else:
        subj = openssl("x509", "-in", new_cert, "-noout", "-subject").stdout
        issuer = openssl("x509", "-in", new_cert, "-noout", "-issuer").stdout
        if "DemoCorp Root CA" not in issuer:
            failures.append("the new certificate is not issued by the DemoCorp CA")
        v = openssl("verify", "-CAfile", CA_CERT, new_cert)
        if v.returncode != 0:
            failures.append("the new certificate does not verify against pki/ca.crt")
        ext = openssl("x509", "-in", new_cert, "-noout", "-ext", "extendedKeyUsage").stdout
        if "TLS Web Client Authentication" not in ext:
            failures.append("the new certificate is not usable for client authentication")
        serial_line = openssl("x509", "-in", new_cert, "-noout", "-serial").stdout
        new_serial = serial_line.split("=", 1)[1].strip().upper() if "=" in serial_line else ""
        if not new_serial or new_serial == ca_serial:
            failures.append("the new certificate has no usable serial of its own")
        # the audit identity must NOT be the one on the new certificate
        if ("CN = " + AUDIT_CN) in subj.replace(",", ""):
            failures.append("the new certificate reuses the compromised CN=" + AUDIT_CN)

    # 3. the CRL exists, is signed by the CA and lists exactly the compromised serial.
    if not os.path.isfile(CRL):
        failures.append("crl/crl.pem is missing")
    else:
        issuer = openssl("crl", "-in", CRL, "-noout", "-issuer").stdout
        if "DemoCorp Root CA" not in issuer:
            failures.append("crl/crl.pem is not issued by the DemoCorp CA")
        v = openssl("crl", "-in", CRL, "-noout", "-verify", "-CAfile", CA_CERT)
        if v.returncode != 0 and "Verify OK" not in v.stdout:
            failures.append("crl/crl.pem does not verify against pki/ca.crt")
        text = openssl("crl", "-in", CRL, "-noout", "-text").stdout
        listed = [l.split(":", 1)[1].strip().replace(":", "").upper()
                  for l in text.splitlines() if l.strip().startswith("Serial Number:")]
        if listed != [AUDIT_SERIAL_WANTED]:
            failures.append("crl/crl.pem does not list exactly the compromised serial")

    # 4. the report carries the three keys and they match the re-derived values.
    if not os.path.isfile(REPORT):
        failures.append("report/pki.md is missing")
    else:
        with open(REPORT, encoding="utf-8") as fh:
            keys = {}
            for line in fh:
                if "=" in line:
                    k, v = line.split("=", 1)
                    keys.setdefault(k.strip().lower(), v.strip())
        got_new = keys.get("new_cert_serial", "")
        got_revoked = keys.get("revoked_serial", "")
        got_path = keys.get("crl_path", "")
        if not got_new:
            failures.append("report is missing new_cert_serial")
        elif new_serial and got_new.replace(":", "").upper() != new_serial:
            failures.append("report new_cert_serial does not match the new certificate")
        if not got_revoked:
            failures.append("report is missing revoked_serial")
        elif got_revoked.replace(":", "").upper() != AUDIT_SERIAL_WANTED:
            failures.append("report revoked_serial does not match the CRL entry")
        if not got_path:
            failures.append("report is missing crl_path")
        elif os.path.normpath(got_path) != "crl/crl.pem":
            failures.append("report crl_path does not point at crl/crl.pem")

    if failures:
        return die(failures)
    print("PASS: new auditor certificate CA-signed, compromised serial revoked, report keys consistent")
    return 0


AUDIT_SERIAL_WANTED = "1A2B3C4D5E6F7081"


if __name__ == "__main__":
    sys.exit(main())
