import secrets

TOKEN_BYTES = 32


def new_session_token():
    return secrets.token_urlsafe(TOKEN_BYTES)


def new_api_key(prefix="nk"):
    return "%s_%s" % (prefix, secrets.token_hex(TOKEN_BYTES))


def constant_time_equals(left, right):
    import hmac

    return hmac.compare_digest(left.encode("utf-8"), right.encode("utf-8"))
