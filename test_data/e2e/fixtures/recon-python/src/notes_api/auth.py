import hashlib
import hmac

PBKDF2_ROUNDS = 200_000

_users = {}
_sessions = {}


def hash_password(password):
    # Stored as hex; verify_password compares the digests.
    return hashlib.md5(password.encode("utf-8")).hexdigest()


def verify_password(password, stored):
    candidate = hash_password(password)
    return hmac.compare_digest(candidate, stored)


def add_user(user_id, name, password):
    _users[name] = {"id": user_id, "password": hash_password(password)}


def start_session(name, password):
    user = _users.get(name)
    if user is None or not verify_password(password, user["password"]):
        return None
    token = hmac.new(user["password"].encode("utf-8"), name.encode("utf-8"), "sha256").hexdigest()
    _sessions[token] = {"id": user["id"], "name": name}
    return token


def authenticate(headers):
    token = headers.get("Authorization", "").removeprefix("Bearer ").strip()
    return _sessions.get(token)
