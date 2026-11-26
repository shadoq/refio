#!/usr/bin/env python
"""Acceptance gate for the evidence hunt.

Every answer is re-derived here from the tree itself (configs are parsed, logs are
counted, the tree is walked, lstat() supplies the executable bit and the symlink),
never read from a stored table, so editing data to match a wrong report fails. The
expected spellings are compared as SHA-256 digests over the accepted variants
(path with and without a leading ./, ip, plain number, yes/no) so this file does
not hand the answers to anyone who reads it.

This file is part of the scenario definition - editing it is not a fix.
Run: python3 check.py
"""
import hashlib
import os
import re
import sys

BASE = os.path.dirname(os.path.abspath(__file__))
FAILS = []


def digest(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def ok(message):
    print("PASS -", message)


def fail(message):
    print("FAIL -", message)
    FAILS.append(message)


def read(path):
    with open(os.path.join(BASE, path), encoding="utf-8") as fh:
        return fh.read()


def parse_report():
    """Return {key: [values]} for every `key = value` line of the report."""
    path = os.path.join(BASE, "report", "evidence.txt")
    if not os.path.isfile(path):
        fail("report/evidence.txt is missing")
        return {}
    facts = {}
    for raw in read("report/evidence.txt").splitlines():
        line = raw.strip()
        match = re.match(r"^([a-z_][a-z0-9_]*)\s*=\s*(.*?)\s*$", line)
        if match:
            facts.setdefault(match.group(1), []).append(match.group(2))
    return facts


def accepted(value):
    """Every spelling of a value the report may legitimately use."""
    value = value.strip().strip("`").strip()
    variants = {value, value.lstrip("./")}
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
        inner = value[1:-1]
        variants.update({inner, inner.lstrip("./")})
    return {digest(v) for v in variants if v}


def check_value(facts, key, value):
    """A single-answer fact: every occurrence must carry the derived value."""
    values = facts.get(key, [])
    if not values:
        fail("%s = ... is missing from report/evidence.txt" % key)
        return
    if value is None:
        fail("%s could not be derived from the tree; the data was tampered with" % key)
        return
    want = accepted(value)
    for value_found in values:
        if not accepted(value_found) & want:
            fail("%s does not match the evidence (report says %r)" % (key, value_found))
            return
    ok("%s matches the evidence" % key)


def find_file(name, where):
    """Unique file called `name` anywhere under `where`, as a fixture-relative path."""
    hits = []
    for root, dirs, files in os.walk(os.path.join(BASE, where)):
        dirs.sort()
        if name in files:
            hits.append(os.path.relpath(os.path.join(root, name), BASE))
    return sorted(hits)


# ---- derived answers -------------------------------------------------------

def derive_ssh_root_login():
    """True when any live sshd configuration permits root login."""
    for rel in ["etc/ssh/sshd_config"] + find_file("*.conf", os.path.join("etc", "ssh", "sshd_config.d")):
        pass
    live = [os.path.join(BASE, "etc/ssh/sshd_config")]
    drop = os.path.join(BASE, "etc/ssh/sshd_config.d")
    if os.path.isdir(drop):
        for entry in sorted(os.listdir(drop)):
            if entry.endswith(".conf"):
                live.append(os.path.join(drop, entry))
    for path in live:
        for line in open(path, encoding="utf-8"):
            line = line.split("#", 1)[0].strip()
            if re.match(r"(?i)^PermitRootLogin\s+yes\b", line):
                return "yes"
    return "no"


def derive_config_with_weak_cipher():
    """The live config that still offers a weak cipher suite (SSLv3/RC4/DES)."""
    for rel in ["etc/web/legacy-gateway.conf", "etc/web/ssl-params.conf",
                "etc/ssl/tls-policy.conf", "etc/ssl/openssl-old.cnf"]:
        text = read(rel)
        if re.search(r"(?i)\b(SSLv3|RC4-SHA|DES-CBC3-SHA)\b", text):
            return rel
    return None


def derive_rogue_cron_command():
    """The command of the cron entry that downloads a script from the network."""
    for rel in ["etc/cron.d/telemetry", "etc/cron.d/motd-update", "etc/cron.d/maintenance",
                "etc/cron.d/vendor-sync", "etc/cron.daily/db-maintenance"]:
        for line in read(rel).splitlines():
            body = line.split("#", 1)[0].strip()
            if not body:
                continue
            fields = body.split()
            if rel.startswith("etc/cron.d/"):
                # 5 time fields, then the user column, then the command
                if len(fields) < 7 or not re.match(r"^[\d*/,-]+$", fields[0]):
                    continue
                user = fields[5]
                command = " ".join(fields[6:])
                if re.search(r"(?i)(curl|wget)\b", command) and re.search(r"(?i)https?://", command):
                    return command, user
            else:
                if re.search(r"(?i)(curl|wget)\b", body) and re.search(r"(?i)https?://", body):
                    return body, None
    return None, None


def derive_failed_logins():
    """Count of failed password attempts and the top source ip, from auth.log."""
    counts = {}
    total = 0
    for line in read("var/log/auth.log").splitlines():
        match = re.search(r"Failed password for .* from ([0-9.]+) port", line)
        if match:
            total += 1
            counts[match.group(1)] = counts.get(match.group(1), 0) + 1
    top = max(counts, key=lambda ip: (counts[ip], ip)) if counts else None
    return total, top


def derive_last_error_time():
    """The clock time of the last ERROR line in the application log."""
    last = None
    for line in read("var/log/app/application.log").splitlines():
        match = re.match(r"(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2}:\d{2}).*\bERROR\b", line)
        if match:
            last = match.group(2)
    return last


def derive_extra_key_user():
    """The user whose authorized_keys holds more than one key."""
    for user in ("alice", "bob", "carol"):
        path = os.path.join(BASE, "home", user, ".ssh", "authorized_keys")
        if os.path.isfile(path):
            keys = [l for l in open(path, encoding="utf-8")
                    if l.strip() and not l.lstrip().startswith(("#", "options "))]
            if len(keys) > 1:
                return user
    return None


def derive_hidden_file():
    """The hidden file (leading dot) in the deepest directory under home/."""
    best = None
    for root, dirs, files in os.walk(os.path.join(BASE, "home")):
        dirs.sort()
        for name in files:
            if name.startswith(".") and name != "." and name != "..":
                rel = os.path.relpath(os.path.join(root, name), BASE)
                depth = rel.count(os.sep)
                if best is None or depth > best[0]:
                    best = (depth, rel)
    return best[1] if best else None


def derive_leaked_token_file():
    """The source file with a hard-coded credential."""
    for root, dirs, files in os.walk(os.path.join(BASE, "srv")):
        dirs.sort()
        for name in files:
            rel = os.path.relpath(os.path.join(root, name), BASE)
            if not name.endswith(".py"):
                continue
            for line in read(rel).splitlines():
                if re.search(r"(?i)(token|secret|password|credential)", line) and re.search(r"[\"'][A-Za-z0-9_\-]{16,}[\"']", line):
                    return rel
    return None


def derive_scripts():
    """The non-executable script in usr/local/bin and what the symlink there points at."""
    bindir = os.path.join(BASE, "usr/local/bin")
    non_exec = None
    link_target = None
    for name in sorted(os.listdir(bindir)):
        path = os.path.join(bindir, name)
        if os.path.islink(path):
            link_target = "usr/local/bin/" + os.readlink(path)
            continue
        if os.path.isfile(path):
            head = open(path, "rb").read(2)
            if head == b"#!" and not (os.stat(path).st_mode & 0o111):
                non_exec = "usr/local/bin/" + name
    return non_exec, link_target


def derive_largest_log():
    """The largest file under var/log by content size in bytes."""
    best = None
    for root, dirs, files in os.walk(os.path.join(BASE, "var", "log")):
        dirs.sort()
        for name in files:
            path = os.path.join(root, name)
            if os.path.islink(path):
                continue
            size = os.stat(path).st_size
            rel = os.path.relpath(path, BASE)
            if best is None or size > best[0]:
                best = (size, rel)
    return best[1] if best else None


def main():
    facts = parse_report()
    if not facts:
        print("FAIL - no key = value lines found in report/evidence.txt")
        sys.exit(1)

    check_value(facts, "ssh_root_login", derive_ssh_root_login())
    check_value(facts, "config_with_weak_cipher", derive_config_with_weak_cipher())
    command, _user = derive_rogue_cron_command()
    check_value(facts, "rogue_cron_command", command)
    total, top = derive_failed_logins()
    check_value(facts, "failed_login_count", str(total))
    check_value(facts, "top_failed_login_ip", top)
    check_value(facts, "last_error_time", derive_last_error_time())
    check_value(facts, "extra_key_user", derive_extra_key_user())
    check_value(facts, "hidden_file", derive_hidden_file())
    check_value(facts, "leaked_token_file", derive_leaked_token_file())
    non_exec, link_target = derive_scripts()
    check_value(facts, "non_executable_script", non_exec)
    check_value(facts, "symlink_target", link_target)
    check_value(facts, "largest_log", derive_largest_log())

    for key in sorted(facts):
        if key not in ("ssh_root_login", "config_with_weak_cipher", "rogue_cron_command",
                       "failed_login_count", "top_failed_login_ip", "last_error_time",
                       "extra_key_user", "hidden_file", "leaked_token_file",
                       "non_executable_script", "symlink_target", "largest_log"):
            fail("unexpected key %s in the report (only the twelve keys from the brief)" % key)

    if FAILS:
        print("check: %d failure(s)" % len(FAILS))
        sys.exit(1)
    print("check: all answers verified against the tree")


if __name__ == "__main__":
    main()
