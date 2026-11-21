"""Convert v1 (INI-like) config text to the v2 (YAML-like) format. See SPEC.md."""
import re

DEFAULTS = {
    "server": [("host", "localhost"), ("port", "8080"), ("debug", "false")],
    "db": [("pool", "5"), ("url", "sqlite://memory")],
    "log": [("level", "info")],
}

_HEADER = re.compile(r"^\[([A-Za-z0-9_.-]+)\]$")
_INLINE = re.compile(r"\s[;#]")
_TRUE = {"true", "yes", "on"}
_FALSE = {"false", "no", "off"}


def _quote(text):
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"') + '"'


def _comment(stripped):
    return stripped if stripped.startswith("#") else "#" + stripped[1:]


def _tokenize(text):
    tokens = []
    lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    for no, raw in enumerate(lines, 1):
        s = raw.strip()
        if not s:
            tokens.append(("blank", no))
        elif s[0] in "#;":
            tokens.append(("comment", no, _comment(s)))
        elif s[0] == "[":
            m = _HEADER.match(s)
            if not m:
                raise ValueError(f"line {no}: malformed line")
            tokens.append(("header", no, m.group(1)))
        else:
            key, sep, rest = s.partition("=")
            key = key.strip()
            if not sep or not key or re.search(r"\s", key):
                raise ValueError(f"line {no}: malformed line")
            v = rest.strip()
            note = None
            if v[:1] in ("'", '"'):
                end = v.find(v[0], 1)
                if end < 0:
                    raise ValueError(f"line {no}: malformed line")
                tail = v[end + 1:].strip()
                value = _quote(v[1:end])
                if tail:
                    if tail[0] not in "#;":
                        raise ValueError(f"line {no}: malformed line")
                    note = _comment(tail)
            else:
                m = _INLINE.search(v)
                if m:
                    note = _comment(v[m.start():].strip())
                    v = v[:m.start()].rstrip()
                low = v.lower()
                if low in _TRUE:
                    value = "true"
                elif low in _FALSE:
                    value = "false"
                elif v == "" or ": " in v or "#" in v:
                    value = _quote(v)
                else:
                    value = v
            tokens.append(("kv", no, key, value, note))
    return tokens


def convert(text):
    tokens = _tokenize(text)
    first = next((i for i, t in enumerate(tokens) if t[0] == "header"), len(tokens))
    out = []

    def emit(line):
        if line == "" and (not out or out[-1] == ""):
            return
        out.append(line)

    for t in tokens[:first]:
        if t[0] == "kv":
            raise ValueError(f"line {t[1]}: setting outside a section")
        emit("" if t[0] == "blank" else t[2])
    bounds = [i for i, t in enumerate(tokens) if t[0] == "header"] + [len(tokens)]

    seen = set()
    for n in range(len(bounds) - 1):
        head = tokens[bounds[n]]
        body = tokens[bounds[n] + 1:bounds[n + 1]]
        name = head[2]
        if name in seen:
            raise ValueError(f"line {head[1]}: duplicate section '{name}'")
        seen.add(name)
        has_next = n + 2 < len(bounds)
        tail_comments = []
        if has_next:
            while body and body[-1][0] == "comment":
                tail_comments.insert(0, body.pop())
        tail_blank = False
        while body and body[-1][0] == "blank":
            body.pop()
            tail_blank = True
        emit(name + ":")
        keys = set()
        for t in body:
            if t[0] == "blank":
                emit("")
            elif t[0] == "comment":
                emit("  " + t[2])
            else:
                if t[2] in keys:
                    raise ValueError(f"line {t[1]}: duplicate key '{t[2]}' in section '{name}'")
                keys.add(t[2])
                line = f"  {t[2]}: {t[3]}"
                if t[4]:
                    line += "  " + t[4]
                emit(line)
        for k, v in DEFAULTS.get(name, []):
            if k not in keys:
                emit(f"  {k}: {v}")
        if tail_blank:
            emit("")
        for t in tail_comments:
            emit(t[2])

    for name, items in DEFAULTS.items():
        if name not in seen:
            emit("")
            emit(name + ":")
            for k, v in items:
                emit(f"  {k}: {v}")
    while out and out[-1] == "":
        out.pop()
    while out and out[0] == "":
        out.pop(0)
    return "\n".join(out) + "\n" if out else ""
