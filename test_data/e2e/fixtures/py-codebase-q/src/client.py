import time

from src.backoff import backoff_delay

MAX_ATTEMPTS = 5


def call_with_retries(fn):
    last_error = None
    for attempt in range(MAX_ATTEMPTS):
        try:
            return fn()
        except ConnectionError as e:
            last_error = e
            time.sleep(backoff_delay(attempt))
    raise last_error
