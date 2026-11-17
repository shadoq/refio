#!/usr/bin/env python3
"""Acceptance gate for the white-team hardening task.

Replays the attacks the fix must survive and the legitimate traffic it must keep serving.
Exit 0 only when every check passes. This file is part of the scenario definition - editing it
is not a fix.

Run: python3 attack_check.py
"""
import os
import sys

BASE = os.path.dirname(os.path.abspath(__file__))
PUBLIC = os.path.join(BASE, "public")
SECRET = b"internal-eyes-only"

# The replacement credential is provided through the environment, like a real rotation would be.
os.environ["ADMIN_PASSWORD"] = "new-secret-99"

sys.path.insert(0, BASE)
import server  # noqa: E402

failures = []


def check(name, ok):
    print(("PASS" if ok else "FAIL"), "-", name)
    if not ok:
        failures.append(name)


def resolves_and_reads(public_dir, name):
    """Return the file content a download of `name` would produce, or None."""
    try:
        path = server.resolve_download_path(public_dir, name)
    except Exception:
        return None
    if path is None:
        return None
    try:
        with open(path, "rb") as fh:
            return fh.read()
    except OSError:
        return None


# --- legitimate traffic must keep working -------------------------------------
check(
    "legit download (readme.txt) still resolves and reads",
    resolves_and_reads(PUBLIC, "readme.txt") is not None,
)

# --- attack 1: path traversal must be refused ---------------------------------
def leaks_secret(name):
    data = resolves_and_reads(PUBLIC, name)
    return data is not None and SECRET in data


check(
    "traversal ../secret.txt does not leak the secret",
    not leaks_secret("../secret.txt"),
)
check(
    "absolute-path attempt does not leak a system file",
    resolves_and_reads(PUBLIC, "/etc/passwd") is None,
)
check(
    "roundtrip traversal ../public/../secret.txt does not leak the secret",
    not leaks_secret("../public/../secret.txt"),
)

# --- attack 2: the hardcoded credential must be gone --------------------------
with open(os.path.abspath(server.__file__), "r", encoding="utf-8") as fh:
    src = fh.read()
check("hardcoded password literal removed from server.py", "hunter2-admin-42" not in src)
check("old password no longer accepted", server.verify_admin("hunter2-admin-42") is False)
check("ADMIN_PASSWORD env value accepted", server.verify_admin("new-secret-99") is True)
check("wrong password still refused", server.verify_admin("nope") is False)

print()
if failures:
    print(f"attack_check: {len(failures)} check(s) FAILED: " + "; ".join(failures))
    sys.exit(1)
print("attack_check: all checks passed")
