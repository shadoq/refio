#!/usr/bin/env python3
"""Licence checker for the Pro edition demo (vendor-locked, no keygen shipped).

Usage: python3 license.py <user> <key>
"""


def _checksum_half(user):
    """First half: 4 uppercase hex digits derived from the whole user name."""
    value = (sum(ord(c) for c in user) * 7 + len(user)) % 65536
    return f"{value:04X}"


def _letters_half(user):
    """Second half: 4 uppercase letters, each from one cycled character of the user name."""
    out = []
    for i in range(4):
        out.append(chr(ord("A") + ((ord(user[i % len(user)]) + i * 7) % 26)))
    return "".join(out)


def validate_key(user, key):
    """True iff key is the canonical XXXX-YYYY key for this user."""
    if user is None or not user:
        return False
    parts = key.strip().split("-")
    if len(parts) != 2:
        return False
    first, second = parts
    if len(first) != 4 or len(second) != 4:
        return False
    if not all(c in "0123456789ABCDEF" for c in first):
        return False
    if not (second.isalpha() and second.isupper()):
        return False
    return first == _checksum_half(user) and second == _letters_half(user)


if __name__ == "__main__":
    import sys

    if len(sys.argv) != 3:
        print("usage: license.py <user> <key>")
        sys.exit(2)
    print("VALID" if validate_key(sys.argv[1], sys.argv[2]) else "INVALID")
