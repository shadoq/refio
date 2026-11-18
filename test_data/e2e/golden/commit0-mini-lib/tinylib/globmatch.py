"""Glob matching for slash separated paths."""
import re


def _translate(seg):
    out = []
    i = 0
    n = len(seg)
    while i < n:
        c = seg[i]
        if c == "*":
            out.append(".*")
        elif c == "?":
            out.append(".")
        elif c == "[":
            k = i + 1
            neg = False
            if k < n and seg[k] == "!":
                neg = True
                k += 1
            j = seg.find("]", k + 1)
            if j == -1:
                out.append(re.escape("["))
            else:
                body = seg[k:j].replace("\\", "\\\\").replace("^", "\\^")
                out.append("[" + ("^" if neg else "") + body + "]")
                i = j
        elif c == "{" and seg.find("}", i) != -1:
            j = seg.find("}", i)
            alts = seg[i + 1:j].split(",")
            out.append("(?:" + "|".join(_translate(a) for a in alts) + ")")
            i = j
        else:
            out.append(re.escape(c))
        i += 1
    return "".join(out)


def _seg_match(pat, name):
    if name.startswith(".") and not pat.startswith("."):
        return False
    return re.fullmatch(_translate(pat), name, re.S) is not None


def _match(ps, xs):
    if not ps:
        return not xs
    if ps[0] == "**":
        if _match(ps[1:], xs):
            return True
        return bool(xs) and not xs[0].startswith(".") and _match(ps, xs[1:])
    if not xs:
        return False
    return _seg_match(ps[0], xs[0]) and _match(ps[1:], xs[1:])


def match(pattern, path):
    return _match(pattern.split("/"), path.split("/"))


def filter(pattern, paths):
    return [p for p in paths if match(pattern, p)]
