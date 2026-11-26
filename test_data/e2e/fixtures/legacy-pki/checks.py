#!/usr/bin/env python3
"""Offline gate for the emergency PKI migration.

Recomputes all eight report keys from pki/ with openssl, then runs verify.sh.
Nothing is hard-coded and nothing is echoed in plain text beyond pass/fail reasons.
Exit code 0 means every check passed.
"""
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

REPORT = os.path.join(HERE, "report/migration.md")

LEGACY_CA = os.path.join(HERE, "pki/legacy/ca.crt")
LEGACY_CERT = os.path.join(HERE, "pki/legacy/server.crt")
LEGACY_CRL = os.path.join(HERE, "pki/legacy/crl.pem")
NEW_CA = os.path.join(HERE, "pki/current/ca.crt")
NEW_CERT = os.path.join(HERE, "pki/current/server.crt")
NEW_CSR = os.path.join(HERE, "pki/current/server.csr")
SERVER_KEY = os.path.join(HERE, "pki/server.key")
CONF = os.path.join(HERE, "proxy/gw.conf")

KEYS = ["old_serial", "new_serial", "old_sig", "new_sig", "crl_entries",
        "trust_anchor", "proxy_cert", "proxy_key"]


def openssl(*args):
    return subprocess.run(["openssl"] + list(args), capture_output=True, text=True)


def die(failures):
    print("FAIL")
    for f in failures:
        print(" - " + f)
    return 1


def serial_of(path):
    out = openssl("x509", "-in", path, "-noout", "-serial").stdout
    return out.split("=", 1)[1].strip().upper() if "=" in out else ""


def sig_of(path):
    out = openssl("x509", "-in", path, "-noout", "-text").stdout
    m = re.search(r"Signature Algorithm:\s*(\S+)", out)
    return m.group(1) if m else ""


def crl_entries(path):
    out = openssl("crl", "-in", path, "-noout", "-text").stdout
    return [l.split(":", 1)[1].strip().replace(":", "").upper()
            for l in out.splitlines() if l.strip().startswith("Serial Number:")]


def conf_value(name):
    with open(CONF, encoding="utf-8") as fh:
        for line in fh:
            if "=" in line:
                key, value = line.split("=", 1)
                if key.strip() == name:
                    return value.strip()
    return ""


def main():
    failures = []

    # --- recompute the eight values from pki/ ---
    for path, what in ((LEGACY_CA, "legacy CA"), (LEGACY_CERT, "legacy server certificate"),
                       (LEGACY_CRL, "legacy CRL"), (NEW_CA, "new CA"),
                       (NEW_CERT, "new server certificate"), (SERVER_KEY, "server key")):
        if not os.path.isfile(path):
            return die([what + " is missing: " + path])

    old_serial = serial_of(LEGACY_CERT)
    new_serial = serial_of(NEW_CERT)
    old_sig = sig_of(LEGACY_CERT)
    new_sig = sig_of(NEW_CERT)
    entries = crl_entries(LEGACY_CRL)
    crl_entries_n = str(len(entries))
    trust_anchor = "pki/current/ca.crt"
    proxy_cert = conf_value("cert")
    proxy_key = conf_value("key")

    # sanity of the recomputed values themselves
    if old_sig != "sha1WithRSAEncryption":
        failures.append("the legacy server certificate is not SHA-1 signed anymore")
    if new_sig != "sha256WithRSAEncryption":
        failures.append("the new server certificate is not SHA-256 signed")
    # the migration must have happened: the new certificate is not the old one
    if old_serial == new_serial:
        failures.append("the new certificate reuses the old serial")
    # the new certificate must verify against the new CA (offline, no clock dependence)
    v = openssl("verify", "-no_check_time", "-CAfile", NEW_CA, NEW_CERT)
    if v.returncode != 0:
        failures.append("the new server certificate does not verify against the new CA")
    if old_serial not in entries:
        failures.append("the legacy CRL does not revoke the old server certificate")
    # the new certificate must come from the same CSR as the old one
    old_csr = openssl("x509", "-in", LEGACY_CERT, "-noout", "-pubkey").stdout
    new_csr = openssl("x509", "-in", NEW_CERT, "-noout", "-pubkey").stdout
    key_pub = openssl("pkey", "-in", SERVER_KEY, "-pubout").stdout
    norm = lambda s: "".join(l for l in s.splitlines() if "-----" not in l)
    if norm(new_csr) != norm(key_pub) or norm(old_csr) != norm(key_pub):
        failures.append("the new certificate is not issued from the same key/CSR as the old one")
    # the new CA must not be the legacy CA
    if serial_of(NEW_CA) == serial_of(LEGACY_CA):
        failures.append("the new CA is the legacy CA")

    # --- the report ---
    if not os.path.isfile(REPORT):
        return die(["report/migration.md is missing"] + failures)
    with open(REPORT, encoding="utf-8") as fh:
        got = {}
        for line in fh:
            m = re.match(r"^(\w+)\s*=\s*(.*?)\s*$", line)
            if m:
                got.setdefault(m.group(1).lower(), m.group(2))
    expected = {
        "old_serial": old_serial,
        "new_serial": new_serial,
        "old_sig": old_sig,
        "new_sig": new_sig,
        "crl_entries": crl_entries_n,
        "trust_anchor": trust_anchor,
        "proxy_cert": proxy_cert,
        "proxy_key": proxy_key,
    }
    for key in KEYS:
        if key not in got:
            failures.append("report is missing " + key)
        elif got[key].replace(":", "").upper() != expected[key].replace(":", "").upper():
            failures.append("report " + key + " does not match the recomputed value")

    if failures:
        return die(failures)

    # --- verify.sh (bash + openssl); its digest must match the shipped gate ---
    import hashlib
    with open(os.path.join(HERE, "verify.sh"), "rb") as fh:
        if hashlib.sha256(fh.read()).hexdigest() != "4532eddcef3c941773d9019d2105b69449a68f1bf3bbd68fca40578994c9a437":
            return die(["verify.sh was modified (digest mismatch)"])
    r = subprocess.run(["bash", os.path.join(HERE, "verify.sh")],
                       capture_output=True, text=True)
    if r.returncode != 0:
        print("FAIL")
        for line in (r.stdout + r.stderr).splitlines():
            if line.strip():
                print(" - " + line.strip())
        return 1
    print("PASS: migration complete, all eight report keys match, verify.sh OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
