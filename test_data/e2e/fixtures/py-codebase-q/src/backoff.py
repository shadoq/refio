BASE_DELAY_SECONDS = 2


def backoff_delay(attempt):
    """Exponential backoff: the base delay doubles with every retry attempt."""
    return BASE_DELAY_SECONDS * (2 ** attempt)
