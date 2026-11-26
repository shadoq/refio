#!/usr/bin/env python3
"""Acceptance check for report/iocs.md (stage 1 loader triage).

Prints PASS and exits 0 when every required IOC line is present and correct. No decoded value
appears here in plain text: the config is re-derived from the public encoded blob with the
gate's own decoder, and every reported value is compared through SHA-256 digests of its
accepted spellings.
"""
import base64
import hashlib
import re
import sys

REPORT = "report/iocs.md"
REQUIRED = ["c2_host", "c2_port", "persistence", "ransom_extension", "mutex", "config_key_hex"]

# The config blob exactly as it travels in the sample's constant pool (public data).
BLOB = "FnAFaJUQU19qS6ZGLVlHZYV8AEVDJJFYEyJNcp1JFFRZGKxuFRJ3Tc1hGE1eSYtZBXFBWrVLO29VBYtbD3FyVcFcTCMTHPciRX4cJ+NQYiktD/Aqeh0aPMkjRT0uPu8/UCEwD/UuZy9iCfAWOzpiP+ATYisgL5MCRjEsK+9gaCSJwyLM0L7R6xjdtOjhsDPBxffSwwf3wfbM2yDVlM3zzS7HvsDJlAXTiOGhwRPir+HfjAz9heLr9V/Dq+PD+wfy36Cfh0SzyKe6l3Cu6auwiGqF4rLci3mYj6Oxt127y7yNp1er2qW3tgyt/qW7swyZxZCvt3Sb6Zfxs0C09K3xoZ5BE1ttT4RPJkFAYbdCD0F/Q7JcH1lAVqkIQRNOertHexohB8M5cAkjEMwrfho4FtYkeAA+DMwqbw0sAtsldAxKfLhWG2tNfqtJGH9adKlTGmJYbq5IAGZOYLxDFm5VYZp8NVVyXIB6IlJnT4N4JFx6TYhrJFt9Tp55LUl3WZli0InI5CDd3b2GtW+tj/fAumjZm/KCsyTUneOMvTLdi7G00Rj8uouy3xrj4If81R+v+Mfwz063qtzyuwD4udS5mXiEw6mKoX+IxcGdtm6E0PCttWud07uwkVqn/5HL5zjBs86LsnyKig=="

# sha256 of every accepted spelling per line.
DIGESTS = {
    "c2_host": [
        "3b25da92affe4b0fe2cbc362c2f1075378ba57d0463066d908f0ff8e18515171"
    ],
    "c2_port": [
        "e998d857e5637aa94c02419c98be82013f1571b2f4be936b9c79eae57c17dd2f",
        "7c1449d49d2ea3e912269cc2a539c1ed324dd642ce08460a670d472510b32b64",
        "b576ef3444350b96b33745ef8d432885735ad80673549175862c89ee799b318a"
    ],
    "persistence": [
        "cc626bc259700b63daceac5a3c21aeafbee7f44032f7b0871b70179fa00452dc",
        "463f48fa840ff5c704130d473f37e6502c467c8ee5400a47b06e99ecebbfdc7a"
    ],
    "ransom_extension": [
        "39987ff44113452f8542b06764a2e9f66e2020c211b05e749e916b12f97fb0b1",
        "4c470b026027fa6e3a0f45c5437240f21ec77960d8475b5cc99c97bbd1a14879"
    ],
    "mutex": [
        "37dba102ac9120ac54e41605a8b8932f8bf094ccf411ecef713720d49b5d642b"
    ],
    "config_key_hex": [
        "cdebadb5aac4d4bb600167c8f4e146c70b3b0bcdab6f2e211a69c343ee764e9a",
        "0fc22d711058fbfeb44388039f33cd2e458bc92bc9e8a36c42e8ad0322256f75",
        "fc42cbcfa62d2cf9f40758afdb43b448517f3b0cbcf41e825931cc33a9a20687",
        "2c20c5cabdaa8af152d26cc2e993946d6c3530b19efd99b920cdac080182d7f3",
        "dfa2ad7721e1b7f3d3e44d46000447d3998e0246a35f3e1089a1fb543745cdb2"
    ]
}


def config_key():
    return bytes(((v << 1) ^ 0x5C) & 0xFF for v in (0x1D, 0x2F, 0x33, 0x2A, 0x45, 0x38))


def decode_blob(blob, key):
    raw = base64.b64decode(blob)
    return bytes(b ^ key[i % len(key)] ^ (i & 0xFF) for i, b in enumerate(raw)).decode("utf-8")


def config():
    cfg = {}
    for line in decode_blob(BLOB, config_key()).splitlines():
        if "=" in line:
            name, _, value = line.partition("=")
            cfg[name.strip()] = value
    return cfg


def derived():
    cfg = config()
    return {
        "c2_host": "".join(chr(int(p)) for p in cfg["host"].split(",")),
        "c2_port": cfg["port"],
        "persistence": base64.b64decode(cfg["ps"]).decode("utf-8"),
        "ransom_extension": cfg["lock"],
        "mutex": cfg["mutex"],
        "config_key_hex": config_key().hex(),
    }


def sha(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


LINE = re.compile(r"^\s*(?:[-*]\s+|\d+\.\s+)?`?([A-Za-z_][A-Za-z0-9_]*)`?\s*=\s*(.+?)\s*$")


def unmark(value):
    v = value.strip()
    while v.startswith("`") and v.endswith("`") and len(v) >= 2:
        v = v[1:-1].strip()
    if len(v) >= 2 and v[0] == v[-1] and v[0] in "\"'":
        v = v[1:-1].strip()
    v = v.strip().rstrip(",;")
    return v


def normalise(name, value):
    v = unmark(value)
    if name == "c2_host":
        v = v.lower().rstrip(".")
    elif name == "ransom_extension":
        v = v.lower()
    elif name == "config_key_hex":
        v = re.sub(r"^0x", "", v, flags=re.IGNORECASE).replace(" ", "").lower()
    return v


def read_report(path):
    found = {}
    with open(path, encoding="utf-8") as fh:
        for raw in fh:
            m = LINE.match(raw.rstrip("\r\n"))
            if not m:
                continue
            name = m.group(1)
            if name not in REQUIRED:
                continue
            found.setdefault(name, []).append(normalise(name, m.group(2)))
    return found


def main():
    expected = derived()
    for name in REQUIRED:
        if sha(expected[name]) not in DIGESTS[name]:
            print("FAIL: gate is inconsistent with its own decoder (%s)" % name)
            return 1

    try:
        with open(REPORT, encoding="utf-8"):
            pass
    except OSError:
        print("FAIL: %s not found. Write it with these lines: %s" % (REPORT, ", ".join(REQUIRED)))
        return 1
    found = read_report(REPORT)
    for name in REQUIRED:
        if name not in found:
            print("FAIL: missing line '%s = ...' in %s (all of these are required: %s)"
                  % (name, REPORT, ", ".join(REQUIRED)))
            return 1
        if len(set(found[name])) != 1:
            print("FAIL: line '%s' appears more than once with different values in %s" % (name, REPORT))
            return 1

    value = found["c2_host"][0]
    if sha(value) not in DIGESTS["c2_host"]:
        if value.startswith("https://") or value.startswith("http://"):
            print("FAIL: c2_host must be the bare host name, not the full URL")
        elif value != expected["c2_host"].lower().rstrip("."):
            print("FAIL: c2_host is wrong (the host is assembled at run time from the ordinal list)")
        return 1

    value = found["c2_port"][0]
    if sha(value) not in DIGESTS["c2_port"]:
        if value in ("443", "80", "8080"):
            print("FAIL: c2_port is a default port of the URL scheme, not the port stored in the config")
        else:
            print("FAIL: c2_port is wrong (the port is stored in the config blob)")
        return 1

    value = found["persistence"][0]
    if sha(value) not in DIGESTS["persistence"]:
        real = expected["persistence"]
        encoded = base64.b64encode(real.encode("utf-8")).decode("ascii")
        if value == encoded or encoded in value:
            print("FAIL: persistence is still Base64-encoded; report the decoded command")
        elif real.startswith(value) and value != real:
            print("FAIL: persistence is truncated; the exact command with all its arguments is required")
        elif len(value) < 60:
            print("FAIL: persistence is truncated; the exact command with all its arguments is required")
        else:
            print("FAIL: persistence does not match the command embedded in the config")
        return 1

    value = found["ransom_extension"][0]
    if sha(value) not in DIGESTS["ransom_extension"]:
        if value == expected["ransom_extension"].lstrip(".") + "s":
            print("FAIL: ransom_extension is wrong (the extension the encryption stage appends, with the dot)")
        else:
            print("FAIL: ransom_extension is wrong (the extension the encryption stage appends)")
        return 1

    value = found["mutex"][0]
    if sha(value) not in DIGESTS["mutex"]:
        print("FAIL: mutex is wrong (the name is stored in the config blob)")
        return 1

    value = found["config_key_hex"][0]
    if sha(value) not in DIGESTS["config_key_hex"]:
        if value == BLOB or BLOB in value:
            print("FAIL: config_key_hex is the encoded blob, not the XOR key. Report the key bytes as hex.")
        else:
            print("FAIL: config_key_hex is wrong (wrong length or wrong bytes)")
        return 1

    print("PASS: all %d lines in %s are correct" % (len(REQUIRED), REPORT))
    return 0


if __name__ == "__main__":
    sys.exit(main())
