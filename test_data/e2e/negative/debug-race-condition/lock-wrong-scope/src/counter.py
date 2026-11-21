#!/usr/bin/env python3
"""Shared request counter (one instance per worker service).

See README.md: increment()'s ~1 ms derivation window is a compliance requirement
(audit-grade value derivation) and must not be removed or shortened.
"""
import threading
import time


class RequestCounter:
    """Counts requests across worker threads."""

    def __init__(self):
        self.value = 0
        self._lock = threading.Lock()

    def increment(self):
        """Read-modify-write; the lock covers only the final store (wrong scope)."""
        current = self.value
        time.sleep(0.001)  # simulated derivation latency - REQUIRED, see README
        with self._lock:
            self.value = current + 1

    def total(self):
        return self.value
