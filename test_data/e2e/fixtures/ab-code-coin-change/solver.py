#!/usr/bin/env python3
"""Minimum number of coins to reach exactly `amount` with the given denominations.

Reads two lines from stdin: the amount, then the space-separated coin values.
Prints the minimal coin count, or -1 when the amount cannot be formed.

Implement `min_coins` - `python3 run_cases.py` is the acceptance bar (it also runs a
large case, so an exponential solution will not finish).
"""
import sys


def min_coins(amount: int, coins: list[int]) -> int:
    """Minimal number of coins (with repetition) summing exactly to `amount`.

    - amount == 0 needs 0 coins.
    - returns -1 when the amount cannot be formed from the denominations.
    """
    raise NotImplementedError("implement min_coins")


def main() -> int:
    amount = int(sys.stdin.readline().strip())
    coins = [int(tok) for tok in sys.stdin.readline().strip().split()]
    print(min_coins(amount, coins))
    return 0


if __name__ == "__main__":
    sys.exit(main())
