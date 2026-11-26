#!/usr/bin/env python3
"""Acceptance gate for the ma-incident containment. Re-derives the expected host
state and report facts; prints FAIL with a reason or PASS."""
import base64
import gzip
import hashlib
import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
AUTH_SHA = "746fcc4ef209b82d68fb68d553a7514c40c48be156327ea5840143d01bc48a89"
IMPLANT_SHA = "6ed17d246d8c7faa6e5be5cf22442b28128b1ebc331448e92ad9d80611a9e4e9"


def read(rel):
    with open(os.path.join(ROOT, rel), encoding="utf-8") as fh:
        return fh.read()


def fail(msg):
    print("FAIL: " + msg)
    return 1


def main():
    # 1. no rogue cron lines anywhere (non-comment lines referencing the implant)
    rogue = re.compile(r"^[^#\n]*/opt/monitord/monitord", re.M)
    for rel in ("etc/cron.d/monitord", "var/spool/cron/crontabs/root"):
        try:
            body = read(rel)
        except OSError:
            return fail(rel + " is missing")
        if rogue.search(body):
            return fail("a cron line still launches /opt/monitord/monitord in " + rel)
    # the legitimate backup job must survive
    if "backup-check" not in read("etc/cron.d/backup-check"):
        return fail("the legitimate backup-check cron file was altered")
    if "backup-check.sh" not in read("etc/cron.d/monitord"):
        return fail("the runbook-kept backup job no longer lives in etc/cron.d/monitord")

    # 2. backdoor account locked, authorized_keys gone, entry kept in etc/users
    try:
        shadow = read("etc/shadow")
    except OSError:
        return fail("etc/shadow is missing")
    m = re.search(r"^svc-backup:(\S*):", shadow, re.M)
    if not m:
        return fail("svc-backup disappeared from etc/shadow")
    if not m.group(1).startswith("!"):
        return fail("svc-backup is not locked (password field must start with !)")
    if "svc-backup" not in read("etc/users"):
        return fail("svc-backup must stay listed in etc/users")
    if os.path.exists(os.path.join(ROOT, "home/svc-backup/.ssh/authorized_keys")):
        keys = read("home/svc-backup/.ssh/authorized_keys").strip()
        if keys:
            return fail("home/svc-backup/.ssh/authorized_keys still holds a key")
    if os.path.exists(os.path.join(ROOT, "opt/monitord/monitord")):
        imp = open(os.path.join(ROOT, "opt/monitord/monitord"), "rb").read()
        if imp.strip():
            return fail("the implant is still in place at opt/monitord/monitord")
    try:
        q = open(os.path.join(ROOT, "quarantine/monitord"), "rb").read()
    except OSError:
        return fail("quarantine/monitord is missing")
    if hashlib.sha256(q).hexdigest() != IMPLANT_SHA:
        return fail("quarantine/monitord digest mismatch (evidence corrupted)")
    try:
        man = read("quarantine/manifest.txt").strip()
    except OSError:
        return fail("quarantine/manifest.txt is missing")
    if IMPLANT_SHA not in man:
        return fail("quarantine/manifest.txt does not carry the implant sha256")

    # 4. ssh config equals the policy modulo allowed keys
    conf, pol = read("etc/ssh/sshd_config"), read("etc/ssh/sshd_config.policy")
    def keys(t):
        d = {}
        for line in t.splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line and " " in line:
                k, _, v = line.partition(" ")
                d.setdefault(k, v.strip())
        return d
    ck, pk = keys(conf), keys(pol)
    for k, v in pk.items():
        if k == "Port":
            continue
        if ck.get(k) != v:
            return fail("sshd_config key " + k + " does not match the policy (" +
                        repr(ck.get(k)) + " vs " + repr(v) + ")")

    # 5. evidence untouched
    if hashlib.sha256(read("var/log/auth.log").encode()).hexdigest() != AUTH_SHA:
        return fail("var/log/auth.log was modified (evidence must be preserved)")

    # 6. reports
    try:
        timeline = read("report/timeline.md")
        actions = read("report/actions.md")
        review = read("report/review.md")
    except OSError as e:
        return fail("missing report: " + str(e))
    ta = re.search(r"^first_access_time = (.+)$", timeline, re.M)
    ip = re.search(r"^attacker_ip = (.+)$", timeline, re.M)
    pc = re.search(r"^persistence_count = (\d+)$", timeline, re.M)
    if not (ta and ip and pc):
        return fail("timeline.md misses first_access_time / attacker_ip / persistence_count lines")
    if ta.group(1).strip() != "2026-03-04T02:14:09Z":
        return fail("first_access_time is wrong: " + ta.group(1))
    if ip.group(1).strip() != "203.0.113.47":
        return fail("attacker_ip is wrong: " + ip.group(1))
    if pc.group(1) != "4":
        return fail("persistence_count is wrong: " + pc.group(1))
    steps = re.findall(r"^action = (\d+) \| (.+)$", actions, re.M)
    nums = [int(n) for n, _ in steps]
    if len(steps) < 5:
        return fail("actions.md lists fewer than 5 action lines")
    if nums != sorted(nums) or len(set(nums)) != len(nums):
        return fail("action lines are not numbered in ascending order")
    # runbook order: cron cut, spool cron, account lock, quarantine, ssh policy
    order_keys = []
    for _, what in steps:
        low = what.lower()
        for i, key in enumerate(["cron", "spool", "lock", "quarant", "ssh"]):
            if key in low:
                order_keys.append(i)
                break
    if order_keys != sorted(order_keys):
        return fail("action lines are not in runbook order")
    if not re.search(r"^residual_risk = (low|medium|high)$", review, re.M):
        return fail("review.md misses residual_risk")
    if not re.search(r"^evidence_preserved = yes$", review, re.M):
        return fail("review.md misses evidence_preserved = yes")
    print("PASS: containment complete, ordered, evidence preserved, reports correct")
    return 0


if __name__ == "__main__":
    sys.exit(main())
