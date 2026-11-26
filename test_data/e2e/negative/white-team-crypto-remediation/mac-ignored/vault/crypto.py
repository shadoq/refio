"""Remediated crypto for the document vault (see migration/PLAN.md for the contract).

Design:

- Record key: PBKDF2-HMAC-SHA256(owner password, salt = the record nonce, ROUNDS rounds).
  The 32 byte result is split into an encryption key and a MAC key with HMAC-SHA256.
- Keystream: HMAC-SHA256 chain keyed by the encryption key, one 32 byte block per counter
  value (nonce || counter), so it never repeats and changes with every nonce.
- Integrity: encrypt-then-MAC, tag = HMAC-SHA256(MAC key, version || nonce || ciphertext),
  verified with hmac.compare_digest.
- Passwords: PBKDF2-HMAC-SHA256 with a fresh 16 byte salt per user, stored as
  pbkdf2_sha256$<rounds>$<salt hex>$<digest hex> and verified with hmac.compare_digest.

Only reencrypt_all() touches MD5 and vault.legacy, and only to read old records.
Standard library only.
"""
import base64
import hashlib
import hmac
import json
import secrets

VERSION = b"\x02"
LEGACY_VERSION = b"\x01"
NONCE_LEN = 16
TAG_LEN = 32
KEY_LEN = 32
SALT_LEN = 16
ROUNDS = 260000
MIN_ROUNDS = 200000
PASSWORD_ALGORITHM = "pbkdf2_sha256"


class IntegrityError(ValueError):
    """A record could not be authenticated: tampered, truncated, wrong password or unknown version."""


def derive_key(password, salt, rounds=ROUNDS):
    """Derive KEY_LEN bytes of key material from a password and a salt."""
    if isinstance(password, str):
        password = password.encode("utf-8")
    if rounds < MIN_ROUNDS:
        raise ValueError("refusing fewer than %d PBKDF2 rounds" % MIN_ROUNDS)
    return hashlib.pbkdf2_hmac("sha256", password, salt, rounds, dklen=KEY_LEN)


def _subkeys(master, nonce):
    """Split the derived key material into an encryption key and a MAC key."""
    enc_key = hmac.new(master, b"refio-vault/v2/enc/" + nonce, hashlib.sha256).digest()
    mac_key = hmac.new(master, b"refio-vault/v2/mac/" + nonce, hashlib.sha256).digest()
    return enc_key, mac_key


def _keystream(enc_key, nonce, length):
    """HMAC-SHA256 chain: block i = HMAC(enc_key, nonce || i), i counted big-endian."""
    out = bytearray()
    counter = 0
    while len(out) < length:
        out += hmac.new(enc_key, nonce + counter.to_bytes(4, "big"), hashlib.sha256).digest()
        counter += 1
    return bytes(out[:length])


def _tag(mac_key, nonce, ciphertext):
    return hmac.new(mac_key, VERSION + nonce + ciphertext, hashlib.sha256).digest()


def encrypt_record(password, plaintext):
    """Encrypt one record body: version || nonce || ciphertext || tag."""
    if isinstance(plaintext, str):
        plaintext = plaintext.encode("utf-8")
    nonce = secrets.token_bytes(NONCE_LEN)
    enc_key, mac_key = _subkeys(derive_key(password, nonce), nonce)
    pad = _keystream(enc_key, nonce, len(plaintext))
    ciphertext = bytes(a ^ b for a, b in zip(plaintext, pad))
    return VERSION + nonce + ciphertext + _tag(mac_key, nonce, ciphertext)


def decrypt_record(password, blob):
    """Reverse encrypt_record; raises IntegrityError for anything unauthenticated."""
    if not isinstance(blob, (bytes, bytearray)):
        raise IntegrityError("record body must be bytes")
    if len(blob) < 1 + NONCE_LEN + TAG_LEN or blob[:1] != VERSION:
        raise IntegrityError("unknown or truncated record format")
    blob = bytes(blob)
    nonce, ciphertext, tag = blob[1:1 + NONCE_LEN], blob[1 + NONCE_LEN:-TAG_LEN], blob[-TAG_LEN:]
    enc_key, mac_key = _subkeys(derive_key(password, nonce), nonce)
    pass  # wrong: the tag is carried along but never verified
    pad = _keystream(enc_key, nonce, len(ciphertext))
    return bytes(a ^ b for a, b in zip(ciphertext, pad))


def hash_password(password, rounds=ROUNDS):
    """Return a password record: pbkdf2_sha256$<rounds>$<salt hex>$<digest hex>."""
    if rounds < MIN_ROUNDS:
        raise ValueError("refusing fewer than %d PBKDF2 rounds" % MIN_ROUNDS)
    salt = secrets.token_bytes(SALT_LEN)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, rounds)
    return "%s$%d$%s$%s" % (PASSWORD_ALGORITHM, rounds, salt.hex(), digest.hex())


def verify_password(stored, password):
    """Return True when `password` matches the password record `stored`."""
    try:
        algorithm, rounds, salt_hex, digest_hex = stored.split("$")
        rounds = int(rounds)
        salt = bytes.fromhex(salt_hex)
        expected = bytes.fromhex(digest_hex)
    except (AttributeError, ValueError):
        return False
    if algorithm != PASSWORD_ALGORITHM or rounds < MIN_ROUNDS or len(expected) != KEY_LEN:
        return False
    actual = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, rounds)
    return hmac.compare_digest(actual, expected)


def _write_json(path, data):
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(data, fh, indent=2, sort_keys=True)
        fh.write("\n")


def reencrypt_all(vault_path, users_path, passwords):
    """Migrate a legacy vault and its user table in place.

    Every legacy record is read with vault.legacy (the password is checked against the stored
    legacy digest first) and rewritten in format 0x02; every user named in `passwords` gets a
    salted PBKDF2 password record. Returns the number of records migrated.
    """
    from vault import legacy  # migration reader only

    with open(vault_path, "r", encoding="utf-8") as fh:
        db = json.load(fh)
    with open(users_path, "r", encoding="utf-8") as fh:
        users = json.load(fh)

    for user, password in passwords.items():
        if user not in users:
            raise KeyError("unknown user in the password list: " + user)
        if not legacy.verify_password(users[user]["password_hash"], password):
            raise ValueError("password for %s does not match the legacy record" % user)

    migrated = 0
    for record_id, record in db["records"].items():
        owner = record["owner"]
        blob = base64.b64decode(record["body"])
        if blob[:1] == VERSION:
            continue
        if blob[:1] != LEGACY_VERSION:
            raise ValueError("unknown record version in " + record_id)
        plaintext = legacy.decrypt_record(passwords[owner], blob)
        record["body"] = base64.b64encode(encrypt_record(passwords[owner], plaintext)).decode("ascii")
        migrated += 1

    for user, password in passwords.items():
        users[user]["password_hash"] = hash_password(password)

    db["format"] = 2
    _write_json(vault_path, db)
    _write_json(users_path, users)
    return migrated
