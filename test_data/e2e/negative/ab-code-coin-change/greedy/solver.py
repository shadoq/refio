#!/usr/bin/env python3
"""Greedy 'solution': always take the largest coin first."""
import sys


def min_coins(amount: int, coins: list[int]) -> int:
    count = 0
    for coin in sorted(coins, reverse=True):
        while amount >= coin:
            amount -= coin
            count += 1
    return -1 if amount else count


def main() -> int:
    amount = int(sys.stdin.readline().strip())
    coins = [int(tok) for tok in sys.stdin.readline().strip().split()]
    print(min_coins(amount, coins))
    return 0


if __name__ == "__main__":
    sys.exit(main())
