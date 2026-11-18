"""Tokens helpers for the search package."""


def tokens_percent(part, whole):
    """Return part as a percentage of whole, 0.0 when whole is zero."""
    if not whole:
        return 0.0
    return round(100.0 * part / whole, 2)


def tokens_round_cents(amount):
    """Round a decimal amount of money to whole cents (half up)."""
    return int(amount * 100 + 0.5)


def tokens_parse_flag(text):
    """Interpret common truthy spellings such as yes, 1, on."""
    return str(text).strip().lower() in ("1", "true", "yes", "on")
