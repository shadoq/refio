"""Semantic versions."""
import re
from collections import namedtuple

Version = namedtuple("Version", "major minor patch pre")

_RE = re.compile(r"v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?")


def parse(text):
    m = _RE.fullmatch(text)
    if not m:
        raise ValueError("invalid version: %r" % (text,))
    pre = tuple(m.group(4).split(".")) if m.group(4) is not None else ()
    if any(p == "" for p in pre):
        raise ValueError("invalid prerelease: %r" % (text,))
    return Version(int(m.group(1)), int(m.group(2)), int(m.group(3)), pre)


def _ident_key(ident):
    if ident.isdigit():
        return (0, int(ident), "")
    return (1, 0, ident)


def _cmp_pre(a, b):
    if not a and not b:
        return 0
    if not a:
        return 1
    if not b:
        return -1
    for x, y in zip(a, b):
        kx, ky = _ident_key(x), _ident_key(y)
        if kx != ky:
            return -1 if kx < ky else 1
    return (len(a) > len(b)) - (len(a) < len(b))


def _cmp(a, b):
    ta, tb = a[:3], b[:3]
    if ta != tb:
        return -1 if ta < tb else 1
    return _cmp_pre(a.pre, b.pre)


def compare(a, b):
    return _cmp(parse(a), parse(b))


def _comparators(token):
    if token == "*":
        return [(">=", Version(0, 0, 0, ()))]
    if token.startswith("^"):
        v = parse(token[1:])
        if v.major > 0:
            upper = Version(v.major + 1, 0, 0, ())
        elif v.minor > 0:
            upper = Version(0, v.minor + 1, 0, ())
        else:
            upper = Version(0, 0, v.patch + 1, ())
        return [(">=", v), ("<", upper)]
    if token.startswith("~"):
        v = parse(token[1:])
        return [(">=", v), ("<", Version(v.major, v.minor + 1, 0, ()))]
    for op in (">=", "<=", ">", "<", "="):
        if token.startswith(op):
            return [(op, parse(token[len(op):]))]
    return [("=", parse(token))]


def satisfies(version, spec):
    v = parse(version)
    comps = []
    for token in spec.replace(",", " ").split():
        comps.extend(_comparators(token))
    for op, target in comps:
        c = _cmp(v, target)
        ok = {">=": c >= 0, "<=": c <= 0, ">": c > 0, "<": c < 0, "=": c == 0}[op]
        if not ok:
            return False
    if v.pre:
        return any(t.pre and t[:3] == v[:3] for _, t in comps)
    return True


def max_satisfying(versions, spec):
    best = None
    for text in versions:
        if satisfies(text, spec):
            if best is None or _cmp(parse(text), parse(best)) > 0:
                best = text
    return best
