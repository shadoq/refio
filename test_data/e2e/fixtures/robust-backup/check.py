#!/usr/bin/env python
"""Acceptance gate for backup.sh.

Builds tricky trees in a temp dir, runs the script with bash, and verifies the whole
docs/backup-spec.md contract: content, symlinks, manifest, exit codes, warnings, the
failure modes (missing destination, unwritable/full destination, interrupted run,
duplicate run) and hygiene (no stray files in the CWD, no leftover temp dirs).

This file is part of the scenario definition - editing it is not a fix.
Run: python3 check.py
"""
import hashlib
import os
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time

BASE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(BASE, "backup.sh")
FAILS = []
KEEP = []


def ok(msg):
    print("PASS -", msg)


def fail(msg):
    print("FAIL -", msg)
    FAILS.append(msg)


def run(src=None, dst=None, extra_env=None, cwd=None, timeout=60):
    cmd = ["bash", SCRIPT]
    if src is not None:
        cmd.append(src)
    if dst is not None:
        cmd.append(dst)
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout,
                          cwd=cwd or BASE, env={**os.environ, **(extra_env or {})})
    return proc.returncode, proc.stdout, proc.stderr


def build_tree(root):
    """The awkward source tree (same shapes make_cases.sh produces)."""
    os.makedirs(os.path.join(root, "plain"))
    os.makedirs(os.path.join(root, "empty dir"))
    files = {
        "plain/hello.txt": "hello\n",
        "Quarterly Report Final.txt": "with spaces\n",
        "plain/tab\tname.txt": "tab separated\n",
        "plain/new\nline.txt": "newline name\n",
        "plain/STAR*name?.txt[1].dat": "globby\n",
        "plain/Uni\xe9code N\xe4me.txt": "unicode\n",
        "plain/zero-byte.bin": "",
        "plain/-rf": "leading dash\n",
        "plain/-not-a-flag": "starts with dash\n",
        "-rf": "top level leading dash\n",
        "deep/a/b/c/d/e/f/g/h/leaf.txt": "deep\n",
    }
    for rel, content in files.items():
        path = os.path.join(root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(content)
    os.symlink("plain/hello.txt", os.path.join(root, "link-to-hello"))
    os.symlink("nowhere-at-all", os.path.join(root, "dangling-link"))
    os.symlink("plain", os.path.join(root, "link-to-dir"))
    os.mkfifo(os.path.join(root, "a-fifo"))
    return files


def snapshot(path):
    """Content map of a copied tree: rel path -> ('f', bytes) or ('l', target)."""
    out = {}
    for root, dirs, files in os.walk(path):
        for name in files + dirs:
            p = os.path.join(root, name)
            rel = os.path.relpath(p, path)
            if os.path.islink(p):
                out[rel] = ("l", os.readlink(p))
            elif os.path.isfile(p):
                with open(p, "rb") as fh:
                    out[rel] = ("f", fh.read())
    return out


def parse_manifest(dest):
    """List of (sha256, relative path) entries from manifest.sha256, honouring the
    sha256sum escaping convention: a line starting with a backslash carries an escaped
    name (backslash-n stands for a newline, double backslash for a backslash)."""
    path = os.path.join(dest, "manifest.sha256")
    if not os.path.isfile(path):
        return None
    entries = []
    with open(path, encoding="utf-8") as fh:
        for raw in fh:
            line = raw.rstrip("\n")
            if not line.strip():
                continue
            escaped = line.startswith("\\")
            if escaped:
                line = line[1:]
            hash_, _, name = line.partition("  ")
            if escaped:
                name = name.replace("\\n", "\n").replace("\\\\", "\\")
            entries.append((hash_, name))
    return entries


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def case_normal_copy(tmp, watch):
    src = os.path.join(tmp, "src")
    dst = os.path.join(tmp, "dst with space")
    files = build_tree(src)
    code, out, err = run(src, dst)
    if code != 0:
        fail("normal copy exits %d, want 0 (stderr: %s)" % (code, err.strip()[:200]))
        return
    snap = snapshot(dst)
    for rel, content in files.items():
        got = snap.get(rel)
        if got is None or got != ("f", content.encode("utf-8")):
            fail("file %r was not copied byte-identical" % rel)
    else:
        ok("all regular files copied byte-identical (spaces, tabs, newlines, glob, unicode, dash, empty, deep)")
    for rel, target in (("link-to-hello", "plain/hello.txt"),
                        ("dangling-link", "nowhere-at-all"),
                        ("link-to-dir", "plain")):
        got = snap.get(rel)
        if got != ("l", target):
            fail("symlink %r not preserved (got %r)" % (rel, got))
    else:
        ok("symlinks copied as links, including the dangling one and the dir link")
    if "a-fifo" in snap:
        fail("the FIFO was copied instead of being skipped")
    else:
        ok("FIFO skipped")
    if not re.search(r"a-fifo", "\n".join([out, err])):
        fail("no warning on stderr mentions the skipped FIFO")
    # manifest
    entries = parse_manifest(dst)
    if entries is None:
        fail("manifest.sha256 is missing from the destination")
    else:
        expect = sorted(
            ((sha256(content.encode("utf-8")), rel) for rel, content in files.items()),
            key=lambda e: e[1].encode("utf-8"))
        if entries != expect:
            fail("manifest does not list every copied regular file with its sha256, "
                 "sorted by relative path (got %d entries, want %d)" % (len(entries), len(expect)))
        else:
            ok("manifest lists every copied regular file with correct sha256, sorted by relative path")
    # stray files in the CWD we ran from
    strays = [n for n in os.listdir(watch) if n not in ("src", "dst with space")]
    if strays:
        fail("stray files created in the working directory: %r" % strays)
    else:
        ok("no stray files in the working directory")
    # idempotency
    before = snapshot(dst)
    code, out, err = run(src, dst)
    if code != 0:
        fail("second run exits %d, want 0" % code)
    elif snapshot(dst) != before:
        fail("second run changed the destination")
    else:
        ok("second run is idempotent")
    return dst


def case_empty_source(tmp):
    src = os.path.join(tmp, "empty-src")
    dst = os.path.join(tmp, "empty-dst")
    os.makedirs(src)
    code, out, err = run(src, dst)
    if code != 0:
        fail("empty source exits %d, want 0 (stderr: %s)" % (code, err.strip()[:200]))
        return
    if not os.path.isdir(dst):
        fail("empty source: destination was not created")
    elif parse_manifest(dst) != []:
        fail("empty source: manifest should exist and be empty")
    else:
        ok("empty source: dest created, empty manifest, exit 0")


def case_usage(tmp):
    code, _, _ = run()
    if code != 2:
        fail("no arguments exits %d, want 2" % code)
    else:
        ok("no arguments exits 2")
    code, _, _ = run(os.path.join(tmp, "nope"), os.path.join(tmp, "d"))
    if code != 2:
        fail("missing source exits %d, want 2" % code)
    else:
        ok("missing source exits 2")
    notdir = os.path.join(tmp, "afile")
    open(notdir, "w").close()
    code, _, _ = run(os.path.join(tmp, "empty-src") if os.path.isdir(os.path.join(tmp, "empty-src")) else tmp, notdir)
    if code != 2:
        fail("destination that is a plain file exits %d, want 2" % code)
    else:
        ok("destination that is a plain file exits 2")


def case_unwritable_dest(tmp):
    src = os.path.join(tmp, "src2")
    build_tree(src)
    ro = os.path.join(tmp, "ro-dst")
    os.makedirs(ro)
    # fill the "quota": leave no room for a new file entry by removing write permission
    os.chmod(ro, 0o555)
    try:
        code, out, err = run(src, ro)
        if code == 0:
            fail("unwritable destination still exits 0")
        elif not err.strip():
            fail("unwritable destination: no error on stderr")
        else:
            ok("unwritable destination exits %d with an error on stderr" % code)
    finally:
        os.chmod(ro, 0o755)


def case_interrupted(tmp):
    """SIGTERM mid-run: the trap must clean the temp dir; a re-run then succeeds."""
    src = os.path.join(tmp, "big")
    os.makedirs(src)
    for i in range(400):
        with open(os.path.join(src, "file-%04d.bin" % i), "wb") as fh:
            fh.write(b"x" * 4096)
    before_tmp = set(os.listdir("/tmp")) if os.path.isdir("/tmp") else set()
    dst = os.path.join(tmp, "kill-dst")
    proc = subprocess.Popen(["bash", SCRIPT, src, dst], stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL, cwd=BASE,
                            preexec_fn=os.setsid)
    time.sleep(0.15)
    os.killpg(os.getpgid(proc.pid), signal.SIGTERM)
    proc.wait(timeout=30)
    leftovers = [t for t in os.listdir("/tmp") if t.startswith("backup.") and t not in before_tmp]
    if leftovers:
        fail("interrupted run left temp dirs behind: %r" % leftovers)
        for t in leftovers:
            shutil.rmtree(os.path.join("/tmp", t), ignore_errors=True)
    else:
        ok("interrupted run leaves no temp dir behind")
    code, out, err = run(src, dst)
    if code != 0:
        fail("re-run after interruption exits %d, want 0 (stderr: %s)" % (code, err.strip()[:200]))
    else:
        ok("re-run after interruption completes with exit 0")


def case_vanished_file(tmp):
    """A file that disappears between listing and copying: warn on stderr, exit 1."""
    src = os.path.join(tmp, "vanish-src")
    dst = os.path.join(tmp, "vanish-dst")
    build_tree(src)
    # the victim is deleted mid-flight, between the listing and the copy
    victim = os.path.join(src, "plain", "hello.txt")
    for i in range(200):
        with open(os.path.join(src, "pad-%03d.txt" % i), "w") as fh:
            fh.write("pad %d\n" % i)
    script = (
        "import os, subprocess, sys, time\n"
        "p = subprocess.Popen(['bash', %r, %r, %r], stdout=subprocess.PIPE, stderr=subprocess.PIPE)\n"
        "time.sleep(0.03)\n"
        "try:\n"
        "    os.remove(%r)\n"
        "except OSError:\n"
        "    pass\n"
        "out, err = p.communicate(timeout=60)\n"
        "sys.stderr.write(err.decode('utf-8', 'replace'))\n"
        "sys.exit(p.returncode)\n"
    ) % (SCRIPT, src, dst, victim)
    proc = subprocess.run([sys.executable, "-c", script], capture_output=True, text=True, timeout=120)
    warned = "hello.txt" in proc.stderr
    copied_rest = os.path.isfile(os.path.join(dst, "plain", "tab\tname.txt"))
    if not warned:
        fail("vanished file produced no warning on stderr naming the file")
    elif not copied_rest:
        fail("vanished file: the rest of the tree was not copied")
    elif proc.returncode != 1:
        fail("vanished file: exit code %d, want 1" % proc.returncode)
    else:
        ok("vanished file: warning on stderr, rest copied, exit 1")


def main():
    if not os.path.isfile(SCRIPT):
        print("FAIL - backup.sh is missing")
        sys.exit(1)
    tmp = tempfile.mkdtemp(prefix="backup-check-")
    watch = tempfile.mkdtemp(prefix="backup-watch-")
    try:
        case_normal_copy(tmp, watch)
        case_empty_source(tmp)
        case_usage(tmp)
        case_unwritable_dest(tmp)
        case_interrupted(tmp)
        case_vanished_file(tmp)
    finally:
        for d in (tmp, watch):
            subprocess.run(["chmod", "-R", "u+rwx", d], stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL)
            shutil.rmtree(d, ignore_errors=True)
    if FAILS:
        print("check: %d failure(s)" % len(FAILS))
        sys.exit(1)
    print("check: backup.sh satisfies the whole contract")


if __name__ == "__main__":
    main()
