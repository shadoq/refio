"""Session store (LRU cap added, but the audit trail still keeps payload copies)."""
from collections import OrderedDict

MAX_LIVE = 2048


class SessionStore:
    """Tracks live sessions under an LRU cap; keeps the full audit trail as before."""

    def __init__(self):
        self._live = OrderedDict()
        self._audit = []   # (sid, payload copy) for every session ever opened
        self._issued = 0

    def open(self, sid, payload):
        self._live[sid] = payload
        self._live.move_to_end(sid)
        while len(self._live) > MAX_LIVE:
            self._live.popitem(last=False)
        self._audit.append((sid, bytes(payload)))
        self._issued += 1

    def close(self, sid):
        self._live.pop(sid, None)

    def get(self, sid):
        if sid not in self._live:
            return None
        self._live.move_to_end(sid)
        return self._live[sid]

    @property
    def issued(self):
        return self._issued
