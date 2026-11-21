#!/usr/bin/env python3
"""Transfer ledger (window dropped to 'fix' the hang)."""
import threading

IN_FLIGHT_SECONDS = 0.0


class Account:
    def __init__(self, acct_id, balance=1000):
        self.id = acct_id
        self.balance = balance
        self.lock = threading.Lock()


def transfer(src, dst, amount):
    """Move amount from src to dst; returns True iff it was executed."""
    with src.lock:
        with dst.lock:
            if src.balance >= amount:
                src.balance -= amount
                dst.balance += amount
                return True
    return False
