#!/usr/bin/env python3
"""Acceptance check for report/analysis.md (licence key analysis).

Prints PASS and exits 0 when every required line is present and correct. Otherwise it prints
the first problem found and exits 1. The expected values are not written here in plain text:
the licence key is recomputed with an independent reference implementation of the check, and
the constants are compared through SHA-256 digests of their accepted spellings.
"""
import base64
import hashlib
import re
import sys

REPORT = "report/analysis.md"
DEVICE = "7f3a-9c21-5e04"
REQUIRED = ["key_for_device", "xor_key_hex", "magic_constant", "accepted_class", "permutation"]

MOD = 1000003
N = 14
SEED = 0x51F3
MASK = 0x7A3F
# The encrypted strings exactly as stored in the class constant pool (public data).
ENC = ["6CFcA0slwCEcVFZbnQ==", "5QtyIyg67GJ+LQ==", "+wd7IyU97AY=", "/QZi", "/RB4JypF7QdiLTIm+W5iIzQ/7BA="]

# sha256 of one accepted spelling per line, plus the extra spellings where they exist.
DIGESTS = {
    "xor_key_hex": [
        "71bd042850a75347048f9ed50f8739466caedf3b5c9ebe211ec8c589e5104b55",
        "86695bdc33ff32f1df3971283e093e6d33b8974d76bba6b3b53cfeda4763afcb",
        "bca9d5e71bce75848975815fd89131d93c8a9e92965b315cc250020b7a3cb82a",
        "84200699ab604f0a30442796d317adf8e6e521fd4b0fea0fd4d6df9b88d74c81",
        "f101758a64346b1738bd4c7b20775ed8cae20741a04a7a14f900269a7ce7adce"
    ],
    "magic_constant": [
        "44d80b9cc30c66686fe823a032a9dc5cbae3083904561d158562c84195e0b7f3"
    ],
    "accepted_class": [
        "b3eacd33433b31b5252351032c9b3e7a2e7aa7738d5decdf0dd6c62680853c06",
        "830ef117e1386dfc8c1c22201c5450b6d9ef60ad6fbbdfdacc5cb923c0f799c9",
        "aef7de28d52977f1b5cd0fecfdc151717610adf41e0aa33b4d9f7522a43337ef",
        "8de0b3c47f112c59745f717a626932264c422a7563954872e237b223af4ad643",
        "003ac509bbccffa80567ae45da7504345e8b94f97255b6fd386ee08dab5a9032"
    ],
    "permutation": [
        "b57407dfecdb7cf7cad3e1a794c187bd1829a0cbe0bc83a2452ca3f546ce4cf2",
        "762f81b50f3fb415d80623df6b76bdb8ddfa5e4f82524b6cab5e682e3071355f"
    ]
}


def xor_key():
    return bytes(((SEED >> (4 * i)) ^ (0x5A + 3 * i)) & 0xFF for i in range(6))


def dec(i, key):
    raw = base64.b64decode(ENC[i])
    return bytes(b ^ key[j % len(key)] for j, b in enumerate(raw)).decode("utf-8")


def java_hash(text):
    h = 0
    for ch in text:
        h = (31 * h + ord(ch)) & 0xFFFFFFFF
    return h


def to_signed32(v):
    return v - (1 << 32) if v & 0x80000000 else v


def constants():
    key = xor_key()
    magic = (to_signed32(java_hash(dec(0, key))) ^ MASK) & 0x7FFFFF
    perm = [(i * 5 + 3) % 12 for i in range(12)]
    primes = []
    n = 2
    while len(primes) < N:
        if all(n % d for d in range(2, int(n ** 0.5) + 1)):
            primes.append(n)
        n += 1
    tags = dec(3, key)
    names = dec(4, key).split(",")
    return key, magic, perm, primes, tags, names


def reference(device):
    """The key the server-class branch accepts for `device`."""
    key, magic, perm, primes, tags, names = constants()
    chars = [device.replace("-", "")[p] for p in perm]
    body = "".join(chars[:6])
    acc = magic
    for i, ch in enumerate(chars):
        acc = (acc + ord(ch) * primes[i]) % MOD
    return "%s-%s-%07d" % (tags[2], body, acc), acc


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
    while v.endswith("*") and v.count("*") % 2 == 1:
        v = v[:-1].rstrip()
    return v


def normalise(name, value):
    v = unmark(value)
    if name == "permutation":
        v = v.strip().strip("[]").replace(" ", "")
    elif name == "accepted_class":
        v = v.lower()
    elif name == "xor_key_hex":
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
    key, magic, perm, primes, tags, names = constants()
    expected = {
        "xor_key_hex": key.hex(),
        "magic_constant": str(magic),
        "accepted_class": names[2].lower(),
        "permutation": ",".join(str(p) for p in perm),
    }
    for name, canon in expected.items():
        if sha(canon) not in DIGESTS[name]:
            print("FAIL: gate is inconsistent with its own reference implementation (%s)" % name)
            return 1
    good_key, sum_only = reference(DEVICE)

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

    value = found["xor_key_hex"][0]
    if sha(value) not in DIGESTS["xor_key_hex"]:
        if value in ENC or any(v in value for v in ENC):
            print("FAIL: xor_key_hex is an encrypted (Base64) constant from the class. "
                  "Report the decoded XOR key as hex, not the stored string.")
        elif value == key.hex().upper() or value == "0x" + key.hex() or value == "0X" + key.hex().upper():
            print("FAIL: xor_key_hex spelling not recognised")
        else:
            print("FAIL: xor_key_hex is wrong (wrong length or wrong bytes)")
        return 1

    value = found["magic_constant"][0]
    if sha(value) not in DIGESTS["magic_constant"]:
        if value.lstrip("-").isdigit() and abs(int(value)) > MOD:
            print("FAIL: magic_constant is a stored or intermediate value, not the constant the "
                  "checksum starts from")
        else:
            print("FAIL: magic_constant is wrong (expected a decimal integer)")
        return 1

    value = found["accepted_class"][0]
    if sha(value) not in DIGESTS["accepted_class"]:
        if value in (tags[0].lower(), names[0].lower(), tags[1].lower(), names[1].lower()):
            print("FAIL: accepted_class names a tier whose branch is closed in this build. "
                  "Check the flags the branches test and how they are computed.")
        else:
            print("FAIL: accepted_class is wrong (use the lower-case tier name)")
        return 1

    value = found["permutation"][0]
    if sha(value) not in DIGESTS["permutation"]:
        if sorted(value.split(",")) == sorted(str(p) for p in perm):
            print("FAIL: permutation has the right entries but the wrong order (index order expected)")
        else:
            print("FAIL: permutation is wrong")
        return 1

    value = found["key_for_device"][0]
    if value != good_key:
        if value == "%07d" % sum_only:
            print("FAIL: key_for_device is only the checksum. The key is tag-body-checksum.")
        elif value.replace("S-", "", 1).replace("T-", "", 1).replace("D-", "", 1) == good_key[2:]:
            print("FAIL: key_for_device uses a tier whose branch is closed in this build")
        elif len(value) == len(good_key) and sum(a != b for a, b in zip(value, good_key)) == 1:
            print("FAIL: key_for_device differs by one character from the key the check accepts")
        else:
            print("FAIL: key_for_device is not accepted by the reference implementation of the check "
                  "(expected tag-body-checksum for device %s)" % DEVICE)
        return 1

    print("PASS: all %d lines in %s are correct" % (len(REQUIRED), REPORT))
    return 0


if __name__ == "__main__":
    sys.exit(main())
