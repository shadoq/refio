"""Stats helpers for the wishlists package."""


def stats_moving_average(values, width):
    """Average of each run of `width` consecutive values."""
    return [sum(values[i:i + width]) / width for i in range(len(values) - width + 1)]


def stats_backoff(attempt, base=0.5, cap=30.0):
    """Delay in seconds before retry number `attempt` (exponential, capped)."""
    return min(cap, base * (2 ** attempt))


def stats_mask_email(address):
    """Hide most of the local part of an email address."""
    local, _, domain = address.partition("@")
    return local[:1] + "***@" + domain
