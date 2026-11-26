#!/usr/bin/env python3
"""Acceptance gate: walk every object in repo/.git and prove no blob
anywhere contains any of the leaked markers or their fragments, while the
tracked files match the golden tree (path set and content) and the working
tree is clean."""
import hashlib
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.join(ROOT, "repo")

MARKERS = [
    b"DEPLOY-KEY-9f3a71cd",
    b"DB-PASSWORD-wq88z2",
    b"API-TOKEN-7310ab",
    b"9f3a71cd",
    b"wq88z2",
    b"7310ab",
]

# Expected post-sanitisation tree (path -> sha256 of content), digests only.
EXPECT_SHA = {
    "README.md": "bdf6f4bdfe5b96334f51f2234f405f3c763e8dd0bf1faae8567f0face7f9e636",
    "conf/api.conf": "50372bcccb1bc03624b02076245f3a45718446c4c3be48435b137f6aed8297b5",
    "conf/db.conf": "82d4e4854872b8a8c789b7e7630fbabe4c83f62c23d1535ac9391d46b62e5aff",
    "secrets/deploy.key": "ddf072175ae5d473d1c4d7c4424331e7fe8cad722e43b8b7e06bff9dff751b1f",
    "src/app.py": "352d5b62364b1846f0c5182811112892cc36cbaeef074e85820d0649301d10f2",
}


def fail(msg):
    print("FAIL: " + msg)
    sys.exit(1)


def git(*args):
    p = subprocess.run(["git", *args], cwd=REPO, capture_output=True, text=True)
    if p.returncode != 0:
        fail("git %s failed: %s" % (args[0], p.stderr.strip()))
    return p.stdout


def check_markers():
    hits = []
    listing = git("cat-file", "--batch-all-objects",
                  "--batch-check=%(objectname) %(objecttype)")
    for line in listing.splitlines():
        parts = line.split()
        if len(parts) != 2 or parts[1] != "blob":
            continue
        data = subprocess.run(["git", "cat-file", "blob", parts[0]], cwd=REPO,
                              capture_output=True).stdout
        for m in MARKERS:
            if m in data:
                hits.append(parts[0])
                break
    if hits:
        fail("%d blob(s) still contain a leaked marker or fragment" % len(hits))


def tree_files(treeish):
    out = {}
    for line in git("ls-tree", "-r", treeish).splitlines():
        meta, path = line.split("\t", 1)
        _, kind, sha = meta.split()
        if kind == "blob":
            out[path] = sha
    return out


def main():
    if not os.path.isdir(REPO):
        fail("repo/ directory missing")
    if not os.path.isdir(os.path.join(REPO, ".git")):
        fail("repo/.git missing (nested repo damaged)")

    check_markers()

    files = tree_files("HEAD")
    if set(files) != set(EXPECT_SHA):
        fail("tracked file set changed: %s vs %s"
             % (sorted(files), sorted(EXPECT_SHA)))
    for path, sha in files.items():
        data = subprocess.run(["git", "cat-file", "blob", sha], cwd=REPO,
                              capture_output=True).stdout
        if hashlib.sha256(data).hexdigest() != EXPECT_SHA[path]:
            fail("content of %s differs from the golden tree" % path)

    status = git("status", "--porcelain")
    if status.strip():
        fail("working tree is dirty: %r" % status.splitlines())

    print("PASS: no object contains a leaked marker or fragment and every "
          "tracked file is byte-identical to the golden tree")
    return 0


if __name__ == "__main__":
    sys.exit(main())
