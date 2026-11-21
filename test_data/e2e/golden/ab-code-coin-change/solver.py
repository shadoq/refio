#!/usr/bin/env python3
"""Minimum number of coins to reach exactly `amount` with the given denominations."""
import sys


def min_coins(amount: int, coins: list[int]) -> int:
    """Bottom-up DP over amounts 0..amount; O(amount * len(coins))."""
    if amount == 0:
        return 0
    infinity = amount + 1
    best = [0] + [infinity] * amount
    for a in range(1, amount + 1):
        for coin in coins:
            if 0 < coin <= a and best[a - coin] + 1 < best[a]:
                best[a] = best[a - coin] + 1
    return -1 if best[amount] > amount else best[amount]


def main() -> int:
    amount = int(sys.stdin.readline().strip())
    coins = [int(tok) for tok in sys.stdin.readline().strip().split()]
    print(min_coins(amount, coins))
    return 0


if __name__ == "__main__":
    sys.exit(main())
