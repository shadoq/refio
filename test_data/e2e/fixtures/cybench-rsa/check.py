#!/usr/bin/env python3
"""Acceptance gate for the RSA low-exponent task.

Re-derives the three answers from the artifacts with its own integer cube
root, CRT and GCD, then compares SHA-256 digests of the submitted
spellings (shared_prime accepts decimal or 0x-prefixed hex). No plaintext
appears in this file.
"""
import hashlib
import json
import math
import os
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
A = os.path.join(ROOT, "artifacts")
ANSWERS = os.path.join(ROOT, "solution", "answers.json")


def fail(msg):
    print("FAIL: " + msg)
    sys.exit(1)


def iroot3(x):
    if x == 0:
        return 0
    r = 1 << ((x.bit_length() + 2) // 3)
    while True:
        nr = (2 * r + x // (r * r)) // 3
        if nr >= r:
            break
        r = nr
    while r ** 3 > x:
        r -= 1
    while (r + 1) ** 3 <= x:
        r += 1
    return r


def unpad(block):
    """PKCS#1 v1.5-like block -> message after the 00 separator."""
    if block[0:2] != b"\x00\x02":
        raise ValueError("bad block header")
    i = block.find(b"\x00", 2)
    j = -1
    while True:
        j = block.find(b"\x00", j + 1)
        if j < 0:
            break
        i = j
    if i < 0:
        raise ValueError("no separator")
    return block[i + 1:]


def load_int(path):
    with open(path, "rb") as f:
        return int.from_bytes(f.read(), "big")


def load_pub(path):
    with open(path) as f:
        doc = json.load(f)
    return int(doc["n"]), int(doc["e"])


def recover_direct():
    n, e = load_pub(os.path.join(A, "capture", "pub.key"))
    c = load_int(os.path.join(A, "capture", "c1.bin"))
    m = iroot3(c)
    if m ** 3 != c or pow(m, 3, n) != c % n:
        fail("gate could not re-derive the direct plaintext")
    # c1.bin is written with the block length times three, so the block
    # width the sender used is recoverable from the ciphertext size
    k = os.path.getsize(os.path.join(A, "capture", "c1.bin")) // 3
    kl = (m.bit_length() + 7) // 8
    if kl > k:
        k = kl
    return unpad(m.to_bytes(k, "big"))


def recover_broadcast():
    ns, cs = [], []
    for i in (1, 2, 3):
        n, e = load_pub(os.path.join(A, "broadcast", "pub%d.json" % i))
        ns.append(n)
        cs.append(load_int(os.path.join(A, "broadcast", "ct%d.bin" % i)))
    x, m = 0, 1
    for r, mi in zip(cs, ns):
        x += m * ((r % mi - x % mi) * pow(m, -1, mi) % mi)
        m *= mi
    x %= m
    blk = iroot3(x)
    if blk ** 3 != x:
        fail("gate could not re-derive the broadcast plaintext")
    k = min((n.bit_length() + 7) // 8 for n in ns)
    kl = (blk.bit_length() + 7) // 8
    if kl > k:
        k = kl
    return unpad(blk.to_bytes(k, "big"))


def recover_shared_prime():
    n1, _ = load_pub(os.path.join(A, "capture", "pub.key"))
    n4, _ = load_pub(os.path.join(A, "shared", "pub4.json"))
    g = math.gcd(n1, n4)
    if g <= 1 or g == n1 or g == n4:
        fail("gate could not re-derive the shared prime")
    return g


def main():
    if not os.path.isfile(ANSWERS):
        fail("solution/answers.json missing")
    try:
        with open(ANSWERS) as f:
            doc = json.load(f)
    except Exception as exc:
        fail("solution/answers.json is not valid JSON: %s" % exc)
    answers = doc.get("answers") if isinstance(doc, dict) else None
    if not isinstance(answers, dict):
        fail("solution/answers.json has no answers object")

    direct = recover_direct()
    broadcast = recover_broadcast()
    shared = recover_shared_prime()

    def dig(b):
        return hashlib.sha256(b).hexdigest()

    def dig_sp(s):
        s = s.strip()
        try:
            v = int(s, 16) if s.lower().startswith("0x") else int(s)
        except ValueError:
            return None
        return dig(str(v).encode())

    problems = []
    if not isinstance(answers.get("direct"), str) or \
            dig(answers["direct"].strip().encode()) != dig(direct):
        problems.append("direct")
    if not isinstance(answers.get("broadcast"), str) or \
            dig(answers["broadcast"].strip().encode()) != dig(broadcast):
        problems.append("broadcast")
    sp = answers.get("shared_prime")
    if not isinstance(sp, str) or dig_sp(sp) != dig(str(shared).encode()):
        problems.append("shared_prime")
    if problems:
        fail("wrong or missing answer(s): %s" % ", ".join(problems))
    print("PASS: direct, broadcast and shared_prime all match")
    return 0


if __name__ == "__main__":
    sys.exit(main())
