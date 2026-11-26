#!/usr/bin/env python3
"""Acceptance gate: re-run openssl verify for every leaf in certs/ against
the root + intermediate chain, re-parse each certificate, then require the
report to carry one block per leaf with the true result keyword, SAN list,
CN and issuer CN. No expected values are stored in plain text."""
import os
import re
import subprocess
import sys

CERTS = "certs"
REPORT = "report/tls-report.txt"
CHAIN = os.path.join(CERTS, "root.pem")


def fail(msg):
    print("FAIL: " + msg)
    sys.exit(1)


def openssl(args):
    return subprocess.run(["openssl"] + args, capture_output=True, text=True)


def leaves():
    return sorted(f[:-4] for f in os.listdir(CERTS)
                  if f.startswith("server-") and f.endswith(".pem"))


def verify_result(leaf):
    r = openssl(["verify", "-CAfile", CHAIN,
                 "-untrusted", os.path.join(CERTS, "intermediate.pem"),
                 "-verify_hostname", "api.internal",
                 os.path.join(CERTS, leaf + ".pem")])
    if r.returncode == 0:
        return "OK"
    m = re.search(r"error (\d+) at \d+ depth lookup: ([^:\n]+)", r.stderr)
    if not m:
        m = re.search(r"error (\d+) at \d+ depth lookup: ([^\n]+)", r.stderr)
    return m.group(2).strip() if m else "unable to get local issuer"


def cert_facts(leaf):
    text = openssl(["x509", "-in", os.path.join(CERTS, leaf + ".pem"),
                    "-noout", "-subject", "-issuer"]).stdout
    san_text = openssl(["x509", "-in", os.path.join(CERTS, leaf + ".pem"),
                        "-noout", "-text"]).stdout
    san = re.findall(r"DNS:([^\s,]+)", san_text)
    cn = re.search(r"subject=.*?CN\s*=\s*(.+)$", text, re.M)
    issuer = re.search(r"issuer=.*?CN\s*=\s*(.+)$", text, re.M)
    return (san, cn.group(1).strip() if cn else "",
            issuer.group(1).strip() if issuer else "")


def main() -> int:
    names = leaves()
    if len(names) != 3:
        fail("internal check - expected 3 leaves (scenario drift)")
    if not os.path.isfile(REPORT):
        fail("report/tls-report.txt not found")
    with open(REPORT, encoding="utf-8") as fh:
        text = fh.read()

    blocks = {}
    cur = None
    for line in text.splitlines():
        if line.startswith("cert:"):
            cur = line.split(":", 1)[1].strip()
            blocks[cur] = {}
        elif cur and ":" in line:
            k, v = line.split(":", 1)
            blocks[cur][k.strip()] = v.strip()

    for name in names:
        if name not in blocks:
            fail("no report block for %s" % name)
        want = verify_result(name)
        san, cn, issuer = cert_facts(name)
        b = blocks[name]
        if b.get("result") != want:
            fail("wrong verify result for %s" % name)
        if b.get("san") != ",".join(san):
            fail("wrong SAN list for %s" % name)
        if b.get("cn") != cn:
            fail("wrong CN for %s" % name)
        if b.get("issuer") != issuer:
            fail("wrong issuer for %s" % name)
    print("PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
