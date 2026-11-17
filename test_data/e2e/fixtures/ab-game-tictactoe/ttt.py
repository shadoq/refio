"""Unbeatable move selection - implement the documented function.

The docstring is the contract (including the depth rule); the unit tests in
test_ttt.py are the acceptance bar (they are not to be modified).
"""

from engine import winner


def best_move(board):
    """Return the index (0-8) X should play on `board` (X to move).

    Optimality rule: X must guarantee the best achievable outcome (win over draw
    over loss) against best play, and among equally-valued moves prefer the
    **fastest win**; when losing anyway, prefer the **slowest loss**. Every test
    position has exactly one such move.

    >>> best_move("XX.O.....")
    2
    >>> best_move("O........")
    4
    """
    raise NotImplementedError("implement best_move")
