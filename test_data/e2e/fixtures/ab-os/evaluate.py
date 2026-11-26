#!/usr/bin/env python3
"""Acceptance gate for the shared-drive tidy-up.

Carries a pristine copy of the original work/ tree (a zlib+base64 manifest
embedded below), replays the eight instructions with its own reference
implementation and compares the result with the tree the agent produced:
same file paths, same content (gzip members compared decompressed), same
symlink targets, no extra files.
"""
import base64
import gzip
import hashlib
import json
import os
import re
import shutil
import sys
import tempfile
import zlib

ROOT = os.path.dirname(os.path.abspath(__file__))
BLOB = "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"

OLD = b"acme-corp.example"
NEW = b"acme.example"
FOOTER = b"\n--\nstatus: reviewed 2026-11-25\n"


def pristine(dst):
    """Materialise the pristine work/ tree from the embedded manifest."""
    man = json.loads(zlib.decompress(base64.b64decode(BLOB)))
    for d in man.get("dirs", []):
        os.makedirs(os.path.join(dst, d), exist_ok=True)
    for rel, b64 in man["files"].items():
        p = os.path.join(dst, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "wb") as f:
            f.write(base64.b64decode(b64))
    for rel, target in man.get("links", {}).items():
        p = os.path.join(dst, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        os.symlink(target, p)


def walk_files(root, sub):
    """Regular files (not symlinks) under root/sub, as (path, rel) pairs."""
    out = []
    base = os.path.join(root, sub)
    for dirpath, dirnames, filenames in os.walk(base):
        for name in filenames:
            p = os.path.join(dirpath, name)
            if os.path.islink(p):
                continue
            out.append((p, os.path.relpath(p, root)))
    return out


def apply_reference(root):
    """Replay the eight instructions on the tree at root."""
    def W(*parts):
        return os.path.join(root, *parts)

    # 1. Replace the domain in text files (no NUL byte) under shared/.
    for p, rel in walk_files(root, "shared"):
        with open(p, "rb") as f:
            data = f.read()
        if b"\x00" in data:
            continue
        if OLD in data:
            with open(p, "wb") as f:
                f.write(data.replace(OLD, NEW))

    # 2. Gzip snapshots of the old CSVs (revision below 3).
    os.makedirs(W("archive", "csv"), exist_ok=True)
    for p, rel in walk_files(root, "projects"):
        if not rel.endswith(".csv"):
            continue
        with open(p, "rb") as f:
            head = f.readline()
        m = re.match(rb"# revision: (\d+)", head)
        if m and int(m.group(1)) < 3:
            proj = rel.split(os.sep)[1]
            with open(p, "rb") as f:
                data = f.read()
            with gzip.open(os.path.join(W("archive", "csv"),
                                        proj + "-" + os.path.basename(rel) + ".gz"),
                           "wb") as f:
                f.write(data)

    # 3. BACKUPS.txt: the .bak files under shared/ with their sizes, BEFORE
    #    they are truncated (order matters).
    baks = []
    for p, rel in walk_files(root, "shared"):
        if rel.endswith(".bak"):
            baks.append((rel, os.path.getsize(p)))
    baks.sort(key=lambda t: t[0])
    with open(W("BACKUPS.txt"), "w") as f:
        for rel, size in baks:
            f.write("- %s (%d bytes)\n" % (rel, size))

    # 4. Truncate the .bak files under shared/ to empty.
    for p, rel in walk_files(root, "shared"):
        if rel.endswith(".bak"):
            open(p, "wb").close()

    # 5. Append the review footer to every notes.md under projects/.
    for p, rel in walk_files(root, "projects"):
        if rel.split(os.sep)[-1] == "notes.md":
            with open(p, "rb") as f:
                data = f.read()
            with open(p, "wb") as f:
                f.write(data + FOOTER)

    # 6. INDEX.md over the regular files under shared/, size descending.
    entries = [(os.path.getsize(p), rel)
               for p, rel in walk_files(root, "shared")]
    entries.sort(key=lambda t: (-t[0], t[1]))
    with open(W("INDEX.md"), "w") as f:
        for size, rel in entries:
            f.write("- %s (%d bytes)\n" % (rel, size))

    # 7. COUNT.txt: newline count over .md and .csv under projects/.
    n = 0
    for p, rel in walk_files(root, "projects"):
        if rel.endswith(".md") or rel.endswith(".csv"):
            with open(p, "rb") as f:
                n += f.read().count(b"\n")
    with open(W("COUNT.txt"), "w") as f:
        f.write("%d\n" % n)

    # 8. MANIFEST.txt: sha256 of every PDF under invoices/.
    rows = []
    for p, rel in walk_files(root, "invoices"):
        if rel.endswith(".pdf"):
            with open(p, "rb") as f:
                rows.append((rel, hashlib.sha256(f.read()).hexdigest()))
    rows.sort()
    with open(W("MANIFEST.txt"), "w") as f:
        for rel, dig in rows:
            f.write("%s %s\n" % (rel, dig))


def snapshot(root):
    """Fingerprint a tree: files by content hash, symlinks by target."""
    files, links = {}, {}
    for dirpath, dirnames, filenames in os.walk(root):
        for name in filenames:
            p = os.path.join(dirpath, name)
            r = os.path.relpath(p, root)
            if os.path.islink(p):
                links[r] = os.readlink(p)
            else:
                with open(p, "rb") as f:
                    data = f.read()
                if r.endswith(".gz"):
                    data = gzip.decompress(data)
                files[r] = hashlib.sha256(data).hexdigest()
    return files, links


def compute_score():
    work = os.path.join(ROOT, "work")
    if not os.path.isdir(work):
        print("FAIL: work/ directory missing")
        return 0.0
    tmp = tempfile.mkdtemp(prefix="ab-os-ref-")
    try:
        pristine(os.path.join(tmp, "work"))
        apply_reference(os.path.join(tmp, "work"))
        exp = snapshot(os.path.join(tmp, "work"))
        act = snapshot(work)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    problems = []
    for r in sorted(set(exp[0]) - set(act[0])):
        problems.append("missing file " + r)
    for r in sorted(set(act[0]) - set(exp[0])):
        problems.append("unexpected file " + r)
    for r in sorted(set(exp[0]) & set(act[0])):
        if exp[0][r] != act[0][r]:
            problems.append("content mismatch " + r)
    for r in sorted(set(exp[1]) - set(act[1])):
        problems.append("missing symlink " + r)
    for r in sorted(set(act[1]) - set(exp[1])):
        problems.append("unexpected symlink " + r)
    for r in sorted(set(exp[1]) & set(act[1])):
        if exp[1][r] != act[1][r]:
            problems.append("symlink target mismatch " + r)
    if problems:
        for p in problems:
            print("FAIL: " + p)
        return 0.0
    print("PASS: work/ matches the expected tree (%d files, %d symlinks)"
          % (len(act[0]), len(act[1])))
    return 1.0


if __name__ == "__main__":
    sys.exit(0 if compute_score() == 1.0 else 1)
