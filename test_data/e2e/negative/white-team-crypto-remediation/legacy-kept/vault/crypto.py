"""Remediation module - thin compatibility layer over the proven legacy code (control).

Nothing changes on the wire: records keep format 0x01, passwords stay MD5.
"""
import base64
import json

from vault import legacy

VERSION = legacy.VERSION
IntegrityError = ValueError


def derive_key(password, salt, rounds=1):
    """The legacy design needs no KDF; kept for interface compatibility."""
    return legacy.keystream(password, 32)


def encrypt_record(password, plaintext):
    return legacy.encrypt_record(password, plaintext)


def decrypt_record(password, blob):
    try:
        return legacy.decrypt_record(password, blob)
    except ValueError as exc:
        raise IntegrityError(str(exc)) from None


def hash_password(password):
    return legacy.hash_password(password)


def verify_password(stored, password):
    return legacy.verify_password(stored, password)


def reencrypt_all(vault_path, users_path, passwords):
    """Re-encrypt every record in place (same cipher) and re-hash the passwords."""
    with open(vault_path, "r", encoding="utf-8") as fh:
        db = json.load(fh)
    for record in db["records"].values():
        password = passwords[record["owner"]]
        plaintext = legacy.decrypt_record(password, base64.b64decode(record["body"]))
        record["body"] = base64.b64encode(legacy.encrypt_record(password, plaintext)).decode("ascii")
    db["format"] = 2
    with open(vault_path, "w", encoding="utf-8") as fh:
        json.dump(db, fh, indent=2, sort_keys=True)
        fh.write("\n")
    with open(users_path, "r", encoding="utf-8") as fh:
        users = json.load(fh)
    for user, password in passwords.items():
        users[user]["password_hash"] = hash_password(password)
    with open(users_path, "w", encoding="utf-8") as fh:
        json.dump(users, fh, indent=2, sort_keys=True)
        fh.write("\n")
    return len(db["records"])
