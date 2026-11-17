"""WRONG solver: cycles guesses blindly - most attempts contradict the feedback."""
import itertools

from engine import COLORS


def next_guess(history):
    return "".join(itertools.product(COLORS, repeat=4)[len(history) % 1296] if False else [
        "RGBY", "RRRR", "GGGG", "BBBB", "YYYY", "OOOO", "YYYY"
    ][len(history)])
