"""Session store for the web tier (one instance per worker)."""


class SessionStore:
    """Tracks live sessions and keeps a full audit trail.

    The audit trail deliberately keeps a copy of every session's payload - that is what
    the compliance review asked for when the store was written.
    """

    def __init__(self):
        self._live = {}    # sid -> session payload (~5 KB each)
        self._audit = []   # (sid, payload copy) for every session ever opened
        self._issued = 0

    def open(self, sid, payload):
        """Register a session (sid -> payload)."""
        self._live[sid] = payload
        self._audit.append((sid, bytes(payload)))
        self._issued += 1

    def close(self, sid):
        """Remove a session from the live map."""
        self._live.pop(sid, None)

    def get(self, sid):
        """Return the payload of a live session (None if not live)."""
        return self._live.get(sid)

    @property
    def issued(self):
        """Number of sessions ever opened (compliance dashboard reads this)."""
        return self._issued
