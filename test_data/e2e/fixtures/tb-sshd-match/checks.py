#!/usr/bin/env python3
"""Acceptance gate for the sshd_config change.

Parses etc/ssh/sshd_config the way sshd does for a single connection:
scalar keywords use the first obtained value, and lines inside a Match block
are only obtained when the block criteria hold for that user (group
membership comes from etc/passwd and etc/group). DenyUsers and AllowUsers
accumulate from every applying context.

Accepts only when: spike is denied; root, dana, lee and ruz are not denied
and their effective settings are byte-for-byte the previous ones (compared
as SHA-256 digests, so a tampered gate or config cannot fake them); and the
intended Match Group contractors block still grants PasswordAuthentication
yes. The gate evaluates only the user-related criteria User, Group and all;
blocks with other criteria (Address and friends) count as non-applying.
"""
import fnmatch
import hashlib
import re
import sys

SCALAR_DEFAULTS = {
    "port": "22",
    "addressfamily": "any",
    "logingracetime": "120",
    "permitrootlogin": "prohibit-password",
    "strictmodes": "yes",
    "maxauthtries": "6",
    "maxsessions": "10",
    "pubkeyauthentication": "yes",
    "passwordauthentication": "yes",
    "authorizedkeysfile": ".ssh/authorized_keys",
    "hostbasedauthentication": "no",
    "ignorerhosts": "yes",
    "kbdinteractiveauthentication": "no",
    "permitemptypasswords": "no",
    "x11forwarding": "no",
    "printmotd": "no",
    "allowtcpforwarding": "yes",
    "clientaliveinterval": "0",
    "clientalivecountmax": "3",
    "usepam": "no",
    "tcpkeepalive": "yes",
}
LIST_KEYWORDS = ("denyusers", "allowusers")

USER_DIGESTS = {
    "dana": "25115412b0b8791ba628c4440aeca1af5fc6e96268cba1bd9c8fc015fa1e8c9f",
    "lee": "25115412b0b8791ba628c4440aeca1af5fc6e96268cba1bd9c8fc015fa1e8c9f",
    "root": "25115412b0b8791ba628c4440aeca1af5fc6e96268cba1bd9c8fc015fa1e8c9f",
    "ruz": "25115412b0b8791ba628c4440aeca1af5fc6e96268cba1bd9c8fc015fa1e8c9f",
}

MUST_DENY = "spike"
MUST_NOT_DENY = ["root", "dana", "lee", "ruz"]


def fail(msg):
    print("FAIL: " + msg)
    sys.exit(1)


def read(path):
    try:
        with open(path, encoding="utf-8") as fh:
            return fh.read()
    except OSError:
        fail("%s is missing or unreadable" % path)


def config_lines(text):
    """Split into logical lines, joining backslash continuations."""
    out = []
    buf = ""
    for raw in text.splitlines():
        stripped = raw.strip()
        if stripped.endswith("\\") and not stripped.startswith("#"):
            buf += raw.rstrip()[:-1] + " "
            continue
        out.append(buf + raw)
        buf = ""
    if buf:
        out.append(buf)
    return out


def strip_comment(line):
    for i, ch in enumerate(line):
        if ch == "#" and (i == 0 or line[i - 1] in " \t"):
            return line[:i]
    return line


def split_patterns(token):
    return [p for p in re.split(r"[,\s]+", token) if p]


def match_list(token, names):
    """sshd pattern list with ! negation over the candidate names."""
    pats = split_patterns(token)
    neg = [p[1:] for p in pats if p.startswith("!")]
    pos = [p for p in pats if not p.startswith("!")]
    for n in names:
        if any(fnmatch.fnmatchcase(n, p) for p in neg):
            return False
    if not pos:
        return False
    return any(fnmatch.fnmatchcase(n, p) for n in names for p in pos)


def block_applies(criteria, user, groups):
    """Every criterion on the Match line must hold for the user."""
    ok = True
    i = 0
    while i < len(criteria):
        crit = criteria[i].lower()
        if crit == "all":
            i += 1
            continue
        if i + 1 >= len(criteria):
            return False
        arg = criteria[i + 1]
        i += 2
        if crit == "user":
            if not match_list(arg, [user]):
                ok = False
        elif crit == "group":
            if not match_list(arg, groups):
                ok = False
        else:
            ok = False
    return ok


def evaluate(text, user, groups):
    """Return (scalars, deny_patterns, allow_patterns, match_blocks)."""
    scalars = {}
    deny = []
    allow = []
    blocks = []
    criteria = None
    for line in config_lines(text):
        line = strip_comment(line).strip()
        if not line:
            continue
        parts = line.split(None, 1)
        kw = parts[0].lower()
        rest = parts[1].strip() if len(parts) > 1 else ""
        if kw == "match":
            criteria = (parts[1] if len(parts) > 1 else "").split()
            blocks.append((criteria, []))
            continue
        if blocks:
            blocks[-1][1].append((kw, rest))
        active = criteria is None or block_applies(criteria, user, groups)
        if not active:
            continue
        if kw in SCALAR_DEFAULTS:
            if kw not in scalars:
                scalars[kw] = rest
        elif kw in LIST_KEYWORDS:
            (deny if kw == "denyusers" else allow).extend(rest.split())
    return scalars, deny, allow, blocks


def load_groups():
    passwd = {}
    for line in read("etc/passwd").splitlines():
        f = line.split(":")
        if len(f) >= 4 and f[0]:
            passwd[f[0]] = f[3]
    gid2name = {}
    members = {}
    for line in read("etc/group").splitlines():
        f = line.split(":")
        if len(f) != 4 or not f[0]:
            continue
        gid2name[f[2]] = f[0]
        members[f[0]] = [m for m in f[3].split(",") if m]

    def groups_of(user):
        g = set()
        gid = passwd.get(user)
        if gid is not None and gid in gid2name:
            g.add(gid2name[gid])
        for gname, mem in members.items():
            if user in mem:
                g.add(gname)
        return g
    return groups_of


def canonical(scalars):
    full = dict(SCALAR_DEFAULTS)
    full.update(scalars)
    return "\n".join("%s %s" % (k, full[k]) for k in sorted(full))


def digest(scalars):
    return hashlib.sha256(canonical(scalars).encode()).hexdigest()


def is_denied(user, deny, allow):
    if any(fnmatch.fnmatchcase(user, p) for p in deny):
        return True
    if allow and not any(fnmatch.fnmatchcase(user, p) for p in allow):
        return True
    return False


def contractors_grant_present(blocks):
    """A Match block on Group contractors still grants PasswordAuthentication
    yes."""
    for criteria, directives in blocks:
        group_ok = False
        i = 0
        while i < len(criteria):
            if criteria[i].lower() == "group" and i + 1 < len(criteria):
                if "contractors" in split_patterns(criteria[i + 1]):
                    group_ok = True
                i += 2
            else:
                i += 1
        if not group_ok:
            continue
        for kw, rest in directives:
            if kw == "passwordauthentication" and rest.split() == ["yes"]:
                return True
    return False


def main():
    text = read("etc/ssh/sshd_config")
    groups_of = load_groups()
    problems = []

    spike_g = groups_of(MUST_DENY)
    if "contractors" not in spike_g:
        fail("scenario drift: %s is not in contractors" % MUST_DENY)
    _, deny, allow, blocks = evaluate(text, MUST_DENY, spike_g)
    if not is_denied(MUST_DENY, deny, allow):
        problems.append("%s is not denied by the effective config" % MUST_DENY)

    for user in MUST_NOT_DENY:
        g = groups_of(user)
        _, deny, allow, _ = evaluate(text, user, g)
        if is_denied(user, deny, allow):
            problems.append("%s must not be denied" % user)
        if user in USER_DIGESTS:
            scalars, _, _, _ = evaluate(text, user, g)
            if digest(scalars) != USER_DIGESTS[user]:
                problems.append("effective settings of %s changed" % user)

    if not contractors_grant_present(blocks):
        problems.append("the Match Group contractors block granting "
                        "PasswordAuthentication yes is gone")

    if problems:
        print("FAIL: " + "; ".join(problems))
        return 1
    print("PASS: spike is denied, everyone else keeps the exact effective "
          "settings, contractors block intact")
    return 0


if __name__ == "__main__":
    sys.exit(main())
