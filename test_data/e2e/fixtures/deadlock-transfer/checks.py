#!/usr/bin/env python3
"""Acceptance gate: the deadly pair must complete within the timeout, with exact balances.

Runs A->B (100) and B->A (50) concurrently. On the lock-ordering bug the transfers
deadlock inside the 50 ms window, the joins time out, and the gate fails; a correct fix
completes both with the exact expected balances.
"""
import os
import sys
import threading

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from src.accounts import Account, transfer  # noqa: E402

JOIN_TIMEOUT = 6.0


def main() -> int:
    a = Account("acct-001")
    b = Account("acct-002")

    results = {}

    def run_a_to_b():
        results["ab"] = transfer(a, b, 100)

    def run_b_to_a():
        results["ba"] = transfer(b, a, 50)

    # daemon threads: a deadlocked pair must not keep the gate process alive
    t1 = threading.Thread(target=run_a_to_b, daemon=True)
    t2 = threading.Thread(target=run_b_to_a, daemon=True)
    t1.start()
    t2.start()
    t1.join(JOIN_TIMEOUT)
    t2.join(JOIN_TIMEOUT)

    if t1.is_alive() or t2.is_alive():
        print("FAIL: transfers deadlocked (join timed out)")
        return 1

    if results != {"ab": True, "ba": True}:
        print(f"FAIL: unexpected transfer outcomes: {results}")
        return 1

    # 1000 - 100 + 50 = 950 ; 1000 + 100 - 50 = 1050 ; total conserved.
    if (a.balance, b.balance) != (950, 1050):
        print(f"FAIL: balances {(a.balance, b.balance)} != (950, 1050)")
        return 1

    print("PASS: opposite-direction transfers completed with exact balances")
    return 0


if __name__ == "__main__":
    sys.exit(main())
