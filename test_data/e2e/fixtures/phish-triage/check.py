#!/usr/bin/env python
"""Acceptance gate for the phish sender triage.

Every verdict, the pixel URL and the forward mismatch pair are re-derived
here from the .eml files themselves (parsed with the email module, the
bodies walked for tracking pixel img tags, forward chains unwrapped one
level), so a report that invents verdicts fails. The reason keywords are
the fixed vocabulary of the brief.

This file is part of the scenario definition - editing it is not a fix.
Run: python3 check.py
"""
import email
import os
import re
import sys
from email import policy

BASE = os.path.dirname(os.path.abspath(__file__))
MAILDIR = os.path.join(BASE, "mail")
COMPANY_DOMAIN = "corp.example.com"
FAILS = []

VERDICTS = ("legit", "suspicious")
REASONS = ("spoofed", "pixel", "forward-mismatch", "lookalike", "clean")


def ok(message):
    print("PASS -", message)


def fail(message):
    print("FAIL -", message)
    FAILS.append(message)


def addr(header):
    """The bare address of a From / Return-Path style header."""
    if header is None:
        return None
    match = re.search(r"<([^>]+)>", header)
    if match:
        return match.group(1).lower()
    return header.strip().lower()


def domain(address):
    return address.rsplit("@", 1)[-1] if address and "@" in address else ""


def bodies(msg):
    """All text bodies of a message (plain and html), recursively."""
    out = []
    if msg.is_multipart():
        for part in msg.walk():
            if part.get_content_type() in ("text/plain", "text/html") and not part.is_multipart():
                out.append(part.get_content())
        # embedded message/rfc822 parts
        for part in msg.walk():
            if part.get_content_type() == "message/rfc822":
                for sub in part.get_payload():
                    out.extend(bodies(sub))
    else:
        out.append(msg.get_content())
    return out


def parse_eml(path):
    with open(path, encoding="utf-8") as fh:
        return email.message_from_file(fh, policy=policy.default)


def inner_messages(msg):
    """message/rfc822 parts of a mail, as parsed messages."""
    out = []
    if msg.is_multipart():
        for part in msg.walk():
            if part.get_content_type() == "message/rfc822":
                payload = part.get_payload()
                if isinstance(payload, list):
                    for sub in payload:
                        out.append(sub)
    return out


def derive_mail(name):
    """Return (verdict, reason, pixel_url, forward_pair) for one .eml."""
    msg = parse_eml(os.path.join(MAILDIR, name))
    from_addr = addr(msg.get("From"))
    ret_addr = addr(msg.get("Return-Path"))
    texts = bodies(msg)

    pixel_url = None
    for text in texts:
        for m in re.finditer(r'<img[^>]+src="([^"]+)"[^>]*>', text, re.I):
            tag = m.group(0)
            if re.search(r'(?:width|height)\s*=\s*"?1"?', tag) or "pixel" in m.group(1).lower():
                if m.group(1).startswith("http"):
                    pixel_url = m.group(1)
                    break
        if pixel_url:
            break

    inner_pair = None
    for sub in inner_messages(msg):
        sub_from = addr(sub.get("From"))
        sub_ret = addr(sub.get("Return-Path"))
        if sub_from and sub_ret and domain(sub_from) != domain(sub_ret):
            inner_pair = (sub_from, sub_ret)
            break

    # verdict rules, in order of strength
    if from_addr and ret_addr and domain(from_addr) != domain(ret_addr):
        return "suspicious", "spoofed", pixel_url, inner_pair
    if pixel_url:
        return "suspicious", "pixel", pixel_url, inner_pair
    if inner_pair:
        return "suspicious", "forward-mismatch", pixel_url, inner_pair
    if from_addr and domain(from_addr) != COMPANY_DOMAIN and \
            COMPANY_DOMAIN.split(".")[0] in domain(from_addr):
        return "suspicious", "lookalike", pixel_url, inner_pair
    return "legit", "clean", pixel_url, inner_pair


def parse_report():
    path = os.path.join(BASE, "report", "senders.txt")
    if not os.path.isfile(path):
        fail("report/senders.txt is missing")
        return {}, {}
    verdict_lines = {}
    keys = {}
    for raw in open(path, encoding="utf-8").read().splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        m = re.match(r"^(\S+\.eml)\s*:\s*(\S+)\s+(\S+)$", line)
        if m:
            verdict_lines[m.group(1)] = (m.group(2), m.group(3))
            continue
        m = re.match(r"^([a-z_][a-z0-9_]*)\s*=\s*(.*?)\s*$", line)
        if m:
            keys.setdefault(m.group(1), []).append(m.group(2))
    return verdict_lines, keys


def main():
    names = sorted(n for n in os.listdir(MAILDIR) if n.endswith(".eml"))
    if not names:
        print("FAIL - no .eml files under mail/")
        sys.exit(1)

    derived = {n: derive_mail(n) for n in names}

    verdict_lines, keys = parse_report()

    # 1. every mail has a verdict line, with the derived verdict and reason
    for name in names:
        verdict, reason, _px, _fw = derived[name]
        got = verdict_lines.get(name)
        if got is None:
            fail("%s is missing from the report" % name)
            continue
        if got[0] != verdict or got[1] != reason:
            fail("%s should be %s %s, report says %s %s" % (name, verdict, reason, got[0], got[1]))
        else:
            ok("%s: %s %s matches the evidence" % (name, verdict, reason))
    extra = set(verdict_lines) - set(names)
    if extra:
        fail("report mentions files that do not exist: %s" % ", ".join(sorted(extra)))

    # 2. pixel_url: exact URL from the tracking pixel body of the pixel mail
    pixel_name = None
    pixel_url = None
    for name in names:
        verdict, reason, px, _fw = derived[name]
        if reason == "pixel":
            pixel_name, pixel_url = name, px
    if pixel_url is None:
        fail("the fixture no longer contains a tracking pixel mail; data tampered")
    else:
        got = keys.get("pixel_url", [])
        if not got:
            fail("pixel_url = ... is missing from the report")
        else:
            good = all(v.strip() == pixel_url for v in got)
            if good:
                ok("pixel_url matches the URL in the %s body" % pixel_name)
            else:
                fail("pixel_url does not match the tracking pixel URL in the body")

    # 3. forward_mismatch: the address pair of the innermost chain step
    pair = None
    for name in names:
        verdict, reason, _px, fw = derived[name]
        if reason == "forward-mismatch":
            pair = fw
    if pair is None:
        fail("the fixture no longer contains a forward chain mismatch; data tampered")
    else:
        want_forms = {"%s vs %s" % (pair[0], pair[1]),
                      "%s -> %s" % (pair[0], pair[1]),
                      "%s/%s" % (pair[0], pair[1])}
        got = keys.get("forward_mismatch", [])
        if not got:
            fail("forward_mismatch = ... is missing from the report")
        else:
            good = all(v.strip() in want_forms for v in got)
            if good:
                ok("forward_mismatch matches the inner chain pair")
            else:
                fail("forward_mismatch does not match the innermost Return-Path vs From pair")

    for key in sorted(keys):
        if key not in ("pixel_url", "forward_mismatch"):
            fail("unexpected key %s in the report (only pixel_url and forward_mismatch)" % key)

    if FAILS:
        print("check: %d failure(s)" % len(FAILS))
        sys.exit(1)
    print("check: sender triage verified against the mails")


if __name__ == "__main__":
    main()
