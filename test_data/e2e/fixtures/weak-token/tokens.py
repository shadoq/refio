#!/usr/bin/env python3
"""Proto-Token auth library (service side). Holds the signing key and the verifier."""
import base64
import time

# Service-side key (rotated quarterly). All services verify with this.
SECRET = b"X0R-k3ystr34m-9d4"


def _xor(key: bytes, data: bytes) -> bytes:
    return bytes(b ^ key[i % len(key)] for i, b in enumerate(data))


def sign(user: str, expiry: int) -> str:
    """Signature over '<user>:<expiry>' - repeating-key XOR, hex-encoded."""
    return _xor(SECRET, f"{user}:{expiry}".encode()).hex()


def issue(user: str, expiry: int) -> str:
    payload = f"{user}|{expiry}|{sign(user, expiry)}"
    return base64.urlsafe_b64encode(payload.encode()).decode().rstrip("=")


def verify(token: str):
    """Return {'user': ..., 'expiry': ...} if the token is well-formed, signed and unexpired."""
    try:
        pad = "=" * (-len(token) % 4)
        payload = base64.urlsafe_b64decode(token + pad).decode()
        user, expiry, sig = payload.split("|")
        expiry = int(expiry)
        if expiry < time.time():
            return None
        if sig != sign(user, expiry):
            return None
        return {"user": user, "expiry": expiry}
    except Exception:
        return None
