"""HTTP retry helper."""
from .clock import sleep_ticks
from .settings import BACKOFF_BASE, BACKOFF_FACTOR, MAX_ATTEMPTS, MAX_DELAY_TICKS


def delay_ticks(attempt):
    """Delay before the next try after the given (1-based) failed attempt."""
    return min(BACKOFF_BASE * BACKOFF_FACTOR ** (attempt - 1), MAX_DELAY_TICKS)


def fetch_with_retry(send):
    """Call send(); on ConnectionError wait and try again, up to MAX_ATTEMPTS attempts in total."""
    for attempt in range(1, MAX_ATTEMPTS + 1):
        try:
            return send()
        except ConnectionError:
            if attempt == MAX_ATTEMPTS:
                raise
            sleep_ticks(delay_ticks(attempt))
