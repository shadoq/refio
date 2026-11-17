"""Event-log helpers for the order dashboard."""


def record_event(event, history=None):
    """Append one event to the log and return the log.

    Starts a fresh log when none is supplied.
    """
    if history is None:
        history = []
    history.append(event)
    return history


def take_recent(events, n):
    """Return and consume up to the most recent n events, oldest first.

    When fewer than n events are available, all of them are taken.
    """
    taken = []
    for _ in range(n):
        taken.insert(0, events.pop())
    return taken
