"""Token check helper."""

import hmac
import os

EXPECTED = os.environ.get("API_TOKEN", "")


def is_authorized(token):
    return hmac.compare_digest(token or "", EXPECTED)
