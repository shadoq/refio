#!/usr/bin/env python3
"""Acceptance gate: re-derive the dangling revision-7 commit from the repo's
own object store (git plumbing only), then verify the working tree.

The gate lists every object in repo/.git, drops the ones reachable from any
ref, and among the leftover commits looks for the one whose docs/runbook.md
says `revision: 7`. The restored repo/docs/runbook.md must be byte-identical
to that commit's copy, recovered-commit.txt must hold exactly its full sha,
and no other tracked file may differ from HEAD.
"""
import hashlib
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.join(ROOT, "repo")


def fail(msg):
    print("FAIL: " + msg)
    sys.exit(1)


def git(*args, cwd=REPO):
    p = subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True)
    if p.returncode != 0:
        fail("git %s failed: %s" % (args[0], p.stderr.strip()))
    return p.stdout


def main():
    if not os.path.isdir(REPO):
        fail("repo/ directory missing")
    reachable = set(git("rev-list", "--all").split())
    listing = git("cat-file", "--batch-all-objects",
                  "--batch-check=%(objectname) %(objecttype)")
    good = None
    for line in listing.splitlines():
        parts = line.split()
        if len(parts) != 2 or parts[1] != "commit":
            continue
        sha = parts[0]
        if sha in reachable:
            continue
        runbook = git("show", sha + ":docs/runbook.md")
        if re.search(r"^revision: 7$", runbook, re.M):
            if good is not None:
                fail("multiple dangling revision-7 commits (scenario drift)")
            good = (sha, runbook)
    if good is None:
        fail("no dangling revision-7 commit found (was the object store pruned?)")
    want_sha, want_runbook = good

    path = os.path.join(REPO, "docs", "runbook.md")
    if not os.path.isfile(path):
        fail("repo/docs/runbook.md not restored")
    with open(path, "rb") as fh:
        got = fh.read()
    if hashlib.sha256(got).hexdigest() != hashlib.sha256(
            want_runbook.encode()).hexdigest():
        fail("repo/docs/runbook.md does not match the derived revision-7 copy")

    rec = os.path.join(REPO, "recovered-commit.txt")
    if not os.path.isfile(rec):
        fail("recovered-commit.txt missing")
    with open(rec) as fh:
        got_sha = fh.read().strip()
    if got_sha != want_sha:
        fail("recovered-commit.txt names %r, expected the dangling commit %r"
             % (got_sha, want_sha))

    status = git("status", "--porcelain")
    bad = [l for l in status.splitlines()
           if l.endswith("docs/runbook.md") is False
           and l.endswith("recovered-commit.txt") is False]
    if bad:
        fail("other tracked files changed: %r" % bad)

    print("PASS: runbook and commit sha match the derived dangling commit")


if __name__ == "__main__":
    main()
