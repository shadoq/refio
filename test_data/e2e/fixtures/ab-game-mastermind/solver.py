"""Mastermind solver - implement the documented function.

The docstring is the contract; `python3 run_game.py` is the acceptance bar
(it plays your solver against the real game and enforces the consistency rule).
"""


def next_guess(history):
    """Return the next 4-color guess (e.g. 'BGYR', repeats allowed).

    `history` is the list of previous attempts as (guess, exact, partial) tuples.
    The returned guess must be CONSISTENT with every entry: scoring it against a
    previous guess must reproduce that attempt's (exact, partial) pair. The game
    must be solved within 7 attempts.
    """
    raise NotImplementedError("implement next_guess")
