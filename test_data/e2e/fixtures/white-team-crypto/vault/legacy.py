"""Legacy crypto for the document vault (in production since 2019).

Kept in the tree so that the pre-migration data files can still be read.
Do not use for new data.
"""
import hashlib

KEYSTREAM_SEED = "refio-vault"
VERSION = b"\x01"
MAC_LEN = 16


def keystream(password, length):
    """Expand the password into `length` bytes of keystream."""
    block = hashlib.md5((password + KEYSTREAM_SEED).encode("utf-8")).digest()
    return (block * (length // len(block) + 1))[:length]


def weak_mac(ciphertext):
    """Integrity tag attached to every record: a plain hash of the ciphertext."""
    return hashlib.md5(ciphertext).digest()


def encrypt_record(password, plaintext):
    """Encrypt one record body: version || ciphertext || MAC."""
    if isinstance(plaintext, str):
        plaintext = plaintext.encode("utf-8")
    pad = keystream(password, len(plaintext))
    ciphertext = bytes(a ^ b for a, b in zip(plaintext, pad))
    return VERSION + ciphertext + weak_mac(ciphertext)


def verify_mac(blob):
    """Return True when the trailing MAC matches the ciphertext."""
    ciphertext, mac = blob[1:-MAC_LEN], blob[-MAC_LEN:]
    return mac == weak_mac(ciphertext)


def decrypt_record(password, blob):
    """Reverse encrypt_record. Refuses a blob whose MAC does not match."""
    if not blob or blob[:1] != VERSION:
        raise ValueError("unknown record version")
    if not verify_mac(blob):
        raise ValueError("MAC mismatch")
    ciphertext = blob[1:-MAC_LEN]
    pad = keystream(password, len(ciphertext))
    return bytes(a ^ b for a, b in zip(ciphertext, pad))


def hash_password(password):
    """Store passwords as a fast digest."""
    return hashlib.md5(password.encode("utf-8")).hexdigest()


def verify_password(stored, password):
    """Return True when `password` matches the stored digest."""
    return stored == hash_password(password)
