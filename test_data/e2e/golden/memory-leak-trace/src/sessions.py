"""Session store for the web tier (one instance per worker)."""
from collections import OrderedDict

MAX_LIVE = 2048  # LRU cap for the live session map (see README incident)


class SessionStore:
    """Tracks live sessions under an LRU cap; the audit trail keeps counts only."""

    def __init__(self):
        self._live = OrderedDict()  # sid -> payload, LRU order (last = most recent)
        self._issued = 0            # every session ever opened (compliance reads this)

    def open(self, sid, payload):
        """Register a session (sid -> payload), evicting the LRU entry past the cap."""
        self._live[sid] = payload
        self._live.move_to_end(sid)
        while len(self._live) > MAX_LIVE:
            self._live.popitem(last=False)
        self._issued += 1

    def close(self, sid):
        """Remove a session from the live map."""
        self._live.pop(sid, None)

    def get(self, sid):
        """Return the payload of a live session (None if not live); refreshes recency."""
        if sid not in self._live:
            return None
        self._live.move_to_end(sid)
        return self._live[sid]

    @property
    def issued(self):
        """Number of sessions ever opened (compliance dashboard reads this)."""
        return self._issued
