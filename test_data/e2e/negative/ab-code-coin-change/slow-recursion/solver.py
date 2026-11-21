#!/usr/bin/env python3
"""Exponential recursion without memoization - correct but far too slow."""
import sys


def min_coins(amount: int, coins: list[int]) -> int:
    if amount == 0:
        return 0
    best = -1
    for coin in coins:
        if coin <= amount:
            sub = min_coins(amount - coin, coins)
            if sub >= 0 and (best < 0 or sub + 1 < best):
                best = sub + 1
    return best


def main() -> int:
    amount = int(sys.stdin.readline().strip())
    coins = [int(tok) for tok in sys.stdin.readline().strip().split()]
    print(min_coins(amount, coins))
    return 0


if __name__ == "__main__":
    sys.exit(main())
