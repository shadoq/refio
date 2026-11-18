"""INI-like configuration parsing."""
import re

_REF = re.compile(r"\$\$\{|\$\{([^}]*)\}")


def _clean(raw, n):
    v = raw.strip()
    if v.startswith('"'):
        end = v.find('"', 1)
        if end == -1:
            raise ValueError("line %d: unterminated quote" % n)
        rest = v[end + 1:].strip()
        if rest and rest[0] not in "#;":
            raise ValueError("line %d: text after closing quote" % n)
        return v[1:end]
    v = re.sub(r"\s[;#].*$", "", v)
    return v.strip()


def _value(data, sec, key, stack):
    ident = (sec, key)
    if ident in stack:
        raise ValueError("circular reference at %s:%s" % ident)
    raw = data[sec][key]

    def sub(m):
        if m.group(0) == "$${":
            return "${"
        ref = m.group(1).strip()
        if ":" in ref:
            s, k = (p.strip() for p in ref.split(":", 1))
        else:
            s, k = sec, ref
        if s not in data or k not in data[s]:
            raise KeyError(ref)
        return _value(data, s, k, stack + (ident,))

    return _REF.sub(sub, raw)


def parse(text, interpolate=True):
    data = {}
    section = ""
    last_key = None
    for n, line in enumerate(text.splitlines(), 1):
        stripped = line.strip()
        if not stripped:
            last_key = None
            continue
        if stripped[0] in "#;":
            continue
        if line[0] in " \t":
            if last_key is None:
                raise ValueError("line %d: unexpected indentation" % n)
            cont = _clean(stripped, n)
            cur = data[section][last_key]
            data[section][last_key] = cont if cur == "" else cur + "\n" + cont
            continue
        if stripped.startswith("["):
            end = stripped.find("]")
            tail = stripped[end + 1:].strip() if end != -1 else ""
            if end == -1 or (tail and tail[0] not in "#;"):
                raise ValueError("line %d: bad section header" % n)
            name = stripped[1:end].strip()
            if not name:
                raise ValueError("line %d: empty section name" % n)
            section = name
            data.setdefault(section, {})
            last_key = None
            continue
        m = re.match(r"([^=:]*)[=:](.*)$", stripped)
        if not m or not m.group(1).strip():
            raise ValueError("line %d: expected key = value" % n)
        key = m.group(1).strip()
        data.setdefault(section, {})[key] = _clean(m.group(2), n)
        last_key = key
    if not interpolate:
        return data
    return {s: {k: _value(data, s, k, ()) for k in kv} for s, kv in data.items()}
