"""Mastermind engine (stdlib only): scoring for the 6-color, 4-peg game.

Colors are the letters RGBYOP; a secret/guess is a 4-character string, repeats
allowed. `score` returns (exact, partial): exact = right color in the right peg;
partial = right color in the wrong peg.
"""

COLORS = "RGBYOP"
PEG_COUNT = 4


def score(secret, guess):
    exact = sum(1 for s, g in zip(secret, guess) if s == g)
    from collections import Counter

    wanted = Counter(secret)
    offered = Counter(guess)
    common = sum(min(wanted[c], offered[c]) for c in wanted)
    return exact, common - exact


def is_valid_guess(guess):
    return len(guess) == PEG_COUNT and all(ch in COLORS for ch in guess)
