#!/usr/bin/env python3
"""Transfer ledger: each account owns a lock; a transfer locks both for its duration."""
import threading
import time

# In-flight processing window per transfer (models the real settlement call latency).
IN_FLIGHT_SECONDS = 0.05


class Account:
    def __init__(self, acct_id, balance=1000):
        self.id = acct_id
        self.balance = balance
        self.lock = threading.Lock()


def _ordered_pair(first, second):
    """Two accounts in a deterministic (id-sorted) order, so every transfer - whatever
    its direction - acquires the same pair of locks in the same order."""
    return sorted((first, second), key=lambda acct: acct.id)


def transfer(src, dst, amount):
    """Move amount from src to dst; returns True iff it was executed."""
    first, second = _ordered_pair(src, dst)
    with first.lock:
        time.sleep(IN_FLIGHT_SECONDS)
        with second.lock:
            if src.balance >= amount:
                src.balance -= amount
                dst.balance += amount
                return True
    return False
