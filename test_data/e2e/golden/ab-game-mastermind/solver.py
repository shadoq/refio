"""Mastermind solver: consistency filter over the candidate space in fixed order."""
import itertools

from engine import COLORS, score


def next_guess(history):
    if not history:
        return "RGBY"
    for candidate in map("".join, itertools.product(COLORS, repeat=4)):
        if all(score(candidate, prev) == (exact, partial)
               for prev, exact, partial in history):
            return candidate
    return "RGBY"
