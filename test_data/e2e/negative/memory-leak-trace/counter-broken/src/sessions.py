"""Session store (audit counter dropped along with the trail - breaks compliance)."""
from collections import OrderedDict

MAX_LIVE = 2048


class SessionStore:
    """Tracks live sessions under an LRU cap; audit trail removed entirely."""

    def __init__(self):
        self._live = OrderedDict()
        self._issued = 0  # never incremented any more - who needed the count anyway

    def open(self, sid, payload):
        self._live[sid] = payload
        self._live.move_to_end(sid)
        while len(self._live) > MAX_LIVE:
            self._live.popitem(last=False)

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
